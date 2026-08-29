package org.tornotron.echno_backend.compliance.ai;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.exception.ComplianceAiException;
import org.tornotron.echno_backend.compliance.CompliancePhase;
import org.tornotron.echno_backend.compliance.domain.ComplianceRule;
import org.tornotron.echno_backend.inspection.ComplianceRiskLevel;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.enums.ProjectType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Batching, without a network.
 *
 * <p>The one seam is {@code callModel}, which is overridden here to return canned response
 * bodies. Everything else is the real service, the real splitting and the real
 * {@link ComplianceResponseReader}, because the behaviour worth pinning down is precisely
 * what happens around those calls: how the catalogue is divided, what order the answers are
 * put back in when they arrive out of order, and what a run does when one call of five
 * fails. All of that is otherwise only observable by making real calls against a rule
 * catalogue large enough to break, which is how the token ceiling stayed unmeasured for as
 * long as it did.
 */
class ComplianceBatchingTest {

    /** Records what each call was asked about, so the split itself can be asserted on. */
    private final List<List<String>> callsMade = new CopyOnWriteArrayList<>();

    private final List<Progress> progressReports = new ArrayList<>();

    /** One progress callback, as a value so a test can compare it. */
    private record Progress(int batchesDone, int batchesTotal, int rulesAssessed, int rulesTotal) {}

    @Test
    void splitsTheCatalogueIntoBatchesOfTheConfiguredSize() {
        OpenAiCompatibleComplianceService service = service(10, 4, batch -> answerFor(batch));

        service.suggestCompliances(project(), "Tamil Nadu", rules(25), this::record);

        assertThat(callsMade).hasSize(3);
        assertThat(callsMade.stream().map(List::size)).containsExactlyInAnyOrder(10, 10, 5);
        assertThat(callsMade.stream().flatMap(List::stream).sorted().toList())
                .as("every rule must be asked about exactly once")
                .isEqualTo(rules(25).stream().map(ComplianceRule::getCode).sorted().toList());
    }

    @Test
    void asksInOneCallWhenBatchingIsSwitchedOff() {
        OpenAiCompatibleComplianceService service = service(0, 4, batch -> answerFor(batch));

        service.suggestCompliances(project(), "Tamil Nadu", rules(25), this::record);

        assertThat(callsMade).hasSize(1);
        assertThat(callsMade.get(0)).hasSize(25);
    }

    /**
     * With the calls overlapping, the last batch can and does finish first. The answer must
     * not depend on that, or the order of the generated compliances would change from run to
     * run for no reason the user could see.
     */
    @Test
    void returnsAnswersInBatchOrderEvenWhenTheCallsFinishOutOfOrder() {
        OpenAiCompatibleComplianceService service = service(10, 4, batch -> {
            // The first batch is the slowest, so completion order is the reverse of batch order.
            sleepQuietly(batch.get(0).getCode().equals("RULE-00") ? 120 : 10);
            return answerFor(batch);
        });

        List<ComplianceSuggestion> suggestions =
                service.suggestCompliances(project(), "Tamil Nadu", rules(25), this::record);

        assertThat(suggestions.stream().map(ComplianceSuggestion::ruleCode).toList())
                .isEqualTo(rules(25).stream().map(ComplianceRule::getCode).toList());
    }

    @Test
    void reportsProgressAfterEachBatch() {
        OpenAiCompatibleComplianceService service = service(10, 4, batch -> answerFor(batch));

        service.suggestCompliances(project(), "Tamil Nadu", rules(25), this::record);

        assertThat(progressReports).containsExactly(
                new Progress(1, 3, 10, 25),
                new Progress(2, 3, 20, 25),
                new Progress(3, 3, 25, 25));
    }

    /**
     * The rule from the truncation work, one level up: a run that did not cover every rule
     * has no result, and must not present the batches it did get through as one.
     *
     * <p>Batch three of five fails. Batches one and two have perfectly good answers in hand,
     * covering ten of twenty-five rules. Handing those back would create ten compliances and
     * show the user a finished result silently missing three fifths of the jurisdiction,
     * which is the exact failure the compliance module exists to prevent.
     */
    @Test
    void oneFailedBatchFailsTheWholeRunAndReturnsNothing() {
        OpenAiCompatibleComplianceService service = service(5, 1, batch -> {
            if (batch.get(0).getCode().equals("RULE-10")) {
                throw new ComplianceAiException("the endpoint returned 503");
            }
            return answerFor(batch);
        });

        assertThatExceptionOfType(ComplianceAiException.class)
                .isThrownBy(() -> service.suggestCompliances(
                        project(), "Tamil Nadu", rules(25), this::record))
                .withMessageContaining("Batch 3 of 5")
                .withMessageContaining("25 rule(s) is incomplete")
                .withMessageContaining("no compliances were generated");
    }

    /**
     * Progress stops where the run stopped. A counter that ran on past the failed batch would
     * be the same lie as returning the partial answer, told in numbers instead.
     */
    @Test
    void progressStopsAtTheFailedBatch() {
        OpenAiCompatibleComplianceService service = service(5, 4, batch -> {
            if (batch.get(0).getCode().equals("RULE-10")) {
                sleepQuietly(80);
                throw new ComplianceAiException("the endpoint returned 503");
            }
            return answerFor(batch);
        });

        assertThatExceptionOfType(ComplianceAiException.class).isThrownBy(() ->
                service.suggestCompliances(project(), "Tamil Nadu", rules(25), this::record));

        assertThat(progressReports)
                .as("only the batches before the failure are progress that happened")
                .hasSize(2);
        assertThat(progressReports.get(1)).isEqualTo(new Progress(2, 5, 10, 25));
    }

    /**
     * A batch whose answer is short is refused by the reader exactly as a whole-catalogue
     * answer would be, so batching does not open a way around the coverage check.
     */
    @Test
    void aBatchThatComesBackShortIsRefused() {
        OpenAiCompatibleComplianceService service = service(10, 4, batch ->
                batch.get(0).getCode().equals("RULE-10")
                        ? answerFor(batch.subList(0, batch.size() - 1))
                        : answerFor(batch));

        assertThatExceptionOfType(ComplianceAiException.class)
                .isThrownBy(() -> service.suggestCompliances(
                        project(), "Tamil Nadu", rules(25), this::record))
                .withMessageContaining("Batch 2 of 3")
                .withMessageContaining("unassessed");
    }

    @Test
    void batchCountMatchesTheNumberOfCallsMade() {
        OpenAiCompatibleComplianceService service = service(10, 4, batch -> answerFor(batch));

        assertThat(service.batchCount(25)).isEqualTo(3);
        assertThat(service.batchCount(10)).isEqualTo(1);
        assertThat(service.batchCount(0)).isZero();

        service.suggestCompliances(project(), "Tamil Nadu", rules(25), this::record);
        assertThat(callsMade).hasSize(service.batchCount(25));
    }

    // -----------------------------------------------------------------------------------

    /** What one call does with the rules it was given. */
    private interface CannedEndpoint {
        String answer(List<ComplianceRule> batch);
    }

    private OpenAiCompatibleComplianceService service(int batchSize,
                                                      int concurrency,
                                                      CannedEndpoint endpoint) {
        ComplianceAiProperties props = new ComplianceAiProperties();
        props.setApiKey("test-key");
        props.setBatchSize(batchSize);
        props.setBatchConcurrency(concurrency);
        return new OpenAiCompatibleComplianceService(props) {
            @Override
            String callModel(Project project, String state, List<ComplianceRule> candidateRules) {
                callsMade.add(candidateRules.stream().map(ComplianceRule::getCode).toList());
                return endpoint.answer(candidateRules);
            }
        };
    }

    private void record(int batchesDone, int batchesTotal, int rulesAssessed, int rulesTotal) {
        progressReports.add(new Progress(batchesDone, batchesTotal, rulesAssessed, rulesTotal));
    }

    /**
     * A complete, well-formed chat-completions body assessing exactly the rules given, built
     * with Jackson so the escaping is the provider's rather than this test's guess at it.
     */
    private static String answerFor(List<ComplianceRule> batch) {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode assessments = mapper.createArrayNode();
        for (ComplianceRule rule : batch) {
            ObjectNode element = assessments.addObject();
            element.put("ruleCode", rule.getCode());
            element.put("applies", true);
            element.put("riskLevel", "high");
            element.putArray("resolutionOptions").add("Apply to the authority");
            element.put("rationale", "Applies to a residential project of this size.");
            element.put("phase", "pre-construction");
        }

        ObjectNode root = mapper.createObjectNode();
        ObjectNode choice = root.putArray("choices").addObject();
        choice.put("finish_reason", "stop");
        choice.putObject("message").put("content", assessments.toString());
        root.putObject("usage").put("completion_tokens", batch.size() * 57);
        return root.toString();
    }

    private static List<ComplianceRule> rules(int count) {
        return IntStream.range(0, count).mapToObj(i -> {
            ComplianceRule rule = new ComplianceRule();
            rule.setCode(String.format("RULE-%02d", i));
            rule.setName("Rule " + i);
            rule.setDescription("Something that must be obtained.");
            rule.setAuthority("Some Authority");
            rule.setPhase(CompliancePhase.PRE_CONSTRUCTION);
            rule.setDefaultRiskLevel(ComplianceRiskLevel.HIGH);
            return rule;
        }).toList();
    }

    private static Project project() {
        Project project = new Project();
        project.setProjectName("Anna Nagar Residency");
        project.setProjectType(ProjectType.RESIDENTIAL);
        project.setProjectAddress("14 Second Avenue, Anna Nagar, Chennai");
        return project;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
