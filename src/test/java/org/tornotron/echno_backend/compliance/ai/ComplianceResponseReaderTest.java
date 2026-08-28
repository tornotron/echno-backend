package org.tornotron.echno_backend.compliance.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.exception.ComplianceAiException;
import org.tornotron.echno_backend.compliance.CompliancePhase;
import org.tornotron.echno_backend.compliance.domain.ComplianceRule;
import org.tornotron.echno_backend.inspection.ComplianceRiskLevel;
import org.tornotron.echno_backend.project.enums.ProjectType;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * A truncated compliance answer must be refused, not quietly turned into "nothing applies".
 *
 * <p>This is the failure mode the compliance module could not survive: past some rule count
 * the model's answer is cut off at the token cap, the old reader sliced it to the last
 * bracket it could find, Jackson threw on the fragment, the throw was swallowed, and the run
 * reported success with zero results. Nothing about that outcome told anyone the catalogue
 * had outgrown the budget, and the symptom (a clean empty result) points at rule curation
 * rather than at truncation.
 *
 * <p>Every case here is a canned provider response, so the checks are proven without a key,
 * without the network and without knowing where the token ceiling actually falls. That last
 * point is deliberate: the ceiling has not been measured, and none of these checks depends
 * on the number.
 */
class ComplianceResponseReaderTest {

    private static final int MAX_TOKENS = 4096;

    private final ComplianceResponseReader reader =
            new ComplianceResponseReader(new ObjectMapper(), MAX_TOKENS);

    private static ComplianceRule rule(String code) {
        ComplianceRule rule = new ComplianceRule();
        rule.setState("Tamil Nadu");
        rule.setProjectType(ProjectType.RESIDENTIAL);
        rule.setPhase(CompliancePhase.PRE_CONSTRUCTION);
        rule.setCode(code);
        rule.setName("Rule " + code);
        rule.setDefaultRiskLevel(ComplianceRiskLevel.HIGH);
        return rule;
    }

    private static List<ComplianceRule> rules(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> rule("TN-" + i))
                .toList();
    }

    /** One assessment element, in the shape the prompt asks for. */
    private static String element(String code) {
        return "{\"ruleCode\":\"" + code + "\",\"applies\":true,\"riskLevel\":\"high\","
                + "\"resolutionOptions\":[\"Apply to the authority\",\"Attach the sanctioned plan\"],"
                + "\"rationale\":\"Required for this project.\",\"phase\":\"pre-construction\"}";
    }

    /** A chat-completions envelope carrying {@code content} and the given stop reason. */
    private static String envelope(String content, String finishReason, Integer completionTokens) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            var root = mapper.createObjectNode();
            var choice = root.putArray("choices").addObject();
            choice.putObject("message").put("role", "assistant").put("content", content);
            if (finishReason != null) {
                choice.put("finish_reason", finishReason);
            }
            if (completionTokens != null) {
                root.putObject("usage").put("completion_tokens", completionTokens);
            }
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String completeAnswer(int ruleCount) {
        return "[" + IntStream.rangeClosed(1, ruleCount)
                .mapToObj(i -> element("TN-" + i))
                .reduce((a, b) -> a + "," + b)
                .orElse("") + "]";
    }

    @Test
    void readsEveryAssessmentOutOfACompleteAnswer() {
        String json = envelope(completeAnswer(6), "stop", 800);

        List<ComplianceSuggestion> suggestions = reader.read(json, rules(6));

        assertThat(suggestions).hasSize(6);
        assertThat(suggestions).extracting(ComplianceSuggestion::ruleCode)
                .containsExactly("TN-1", "TN-2", "TN-3", "TN-4", "TN-5", "TN-6");
        assertThat(suggestions.get(0).resolutionOptions()).hasSize(2);
    }

    @Test
    void stillToleratesTheModelWrappingItsArrayInProseAndAFence() {
        String content = "Here is my assessment:\n```json\n" + completeAnswer(2) + "\n```\nLet me know.";

        assertThat(reader.read(envelope(content, "stop", 300), rules(2))).hasSize(2);
    }

    /**
     * The provider said it stopped at the cap. That is the one signal that needs no guessing
     * at the shape of the answer, and it was read by nothing at all: the old reader took
     * {@code choices[0].message.content} and never looked at {@code finish_reason}, so a
     * cap-truncated answer was indistinguishable from a finished one.
     */
    @Test
    void refusesAnAnswerTheProviderSaysItCutOffAtTheTokenLimit() {
        // Deliberately well formed JSON: only the stop reason gives the truncation away, so
        // this fails unless finish_reason is actually being read.
        String json = envelope(completeAnswer(3), "length", MAX_TOKENS);

        assertThatExceptionOfType(ComplianceAiException.class)
                .isThrownBy(() -> reader.read(json, rules(30)))
                .withMessageContaining("cut short")
                .withMessageContaining("length")
                .withMessageContaining(String.valueOf(MAX_TOKENS));
    }

    @Test
    void readsStopReasonWhenTheProviderNamesTheFieldThatWayInstead() {
        String content = completeAnswer(3);
        String json = "{\"choices\":[{\"message\":{\"content\":" + quote(content)
                + "},\"stop_reason\":\"max_tokens\"}]}";

        assertThatExceptionOfType(ComplianceAiException.class)
                .isThrownBy(() -> reader.read(json, rules(3)))
                .withMessageContaining("cut short");
    }

    /**
     * The truncation with no stop reason to give it away. The answer stops mid-element, so
     * the last {@code ]} in the text closes an element's own {@code resolutionOptions}
     * array rather than the outer one. Slicing to it produces a fragment that ends
     * mid-object: the old reader did exactly that, Jackson threw, and the throw became an
     * empty list and a successful run.
     */
    @Test
    void refusesAnArrayThatStopsMidElement() {
        String content = "[" + element("TN-1") + "," + element("TN-2") + ",{\"ruleCode\":\"TN-3\","
                + "\"applies\":true,\"riskLevel\":\"high\",\"resolutionOptions\":[\"Apply to the "
                + "authority\",\"Attach the sanct";
        String json = envelope(content, null, null);

        assertThatExceptionOfType(ComplianceAiException.class)
                .isThrownBy(() -> reader.read(json, rules(6)))
                .withMessageContaining("never closes");
    }

    @Test
    void refusesAnArrayThatClosesButDoesNotParse() {
        String content = "[" + element("TN-1") + ", {\"ruleCode\": }]";

        assertThatExceptionOfType(ComplianceAiException.class)
                .isThrownBy(() -> reader.read(envelope(content, null, 120), rules(2)))
                .withMessageContaining("not a readable array");
    }

    /**
     * The truncation neither of the other two checks can see: the answer happens to stop on
     * a valid closing bracket and the provider reported no stop reason, so the only thing
     * wrong with it is that rules went unassessed. The prompt asks for one element per
     * candidate rule, which is what makes that detectable without knowing the ceiling.
     */
    @Test
    void refusesAnAnswerThatLeavesCandidateRulesUnassessed() {
        String json = envelope(completeAnswer(2), "stop", 900);

        assertThatExceptionOfType(ComplianceAiException.class)
                .isThrownBy(() -> reader.read(json, rules(6)))
                .withMessageContaining("assessed only 2 of 6")
                .withMessageContaining("TN-3");
    }

    @Test
    void namesOnlyTheFirstFewMissingRulesAndCountsTheRest() {
        String json = envelope(completeAnswer(1), "stop", 200);

        assertThatExceptionOfType(ComplianceAiException.class)
                .isThrownBy(() -> reader.read(json, rules(12)))
                .withMessageContaining("leaving 11 unassessed")
                .withMessageContaining("and 6 more");
    }

    /**
     * An empty array is not "nothing applies": a model that assessed every rule and found
     * none of them applicable answers with one element per rule carrying
     * {@code applies: false}. Reading it as a legitimate outcome is what let a broken run
     * pass for a successful one.
     */
    @Test
    void refusesAnEmptyArrayRatherThanReadingItAsNothingApplies() {
        assertThatExceptionOfType(ComplianceAiException.class)
                .isThrownBy(() -> reader.read(envelope("[]", "stop", 5), rules(4)))
                .withMessageContaining("assessed only 0 of 4");
    }

    @Test
    void acceptsAnAnswerInWhichNothingApplies() {
        String content = "[" + IntStream.rangeClosed(1, 3)
                .mapToObj(i -> "{\"ruleCode\":\"TN-" + i + "\",\"applies\":false,"
                        + "\"rationale\":\"Not applicable.\"}")
                .reduce((a, b) -> a + "," + b)
                .orElse("") + "]";

        List<ComplianceSuggestion> suggestions = reader.read(envelope(content, "stop", 90), rules(3));

        assertThat(suggestions).hasSize(3);
        assertThat(suggestions).allMatch(s -> !s.applies());
    }

    @Test
    void ignoresCodesTheModelInventedAsLongAsEveryCandidateWasAssessed() {
        String content = "[" + element("TN-1") + "," + element("TN-2") + "," + element("TN-INVENTED") + "]";

        assertThat(reader.read(envelope(content, "stop", 400), rules(2))).hasSize(3);
    }

    /**
     * Regression guard on the bracket scan: a rationale that mentions a bracketed clause
     * must not be mistaken for the end of the array.
     */
    @Test
    void doesNotEndTheArrayOnABracketInsideAString() {
        String content = "[{\"ruleCode\":\"TN-1\",\"applies\":true,"
                + "\"rationale\":\"Required under section 3[b] of the Act [see also 4].\","
                + "\"resolutionOptions\":[\"Apply\"]},"
                + "{\"ruleCode\":\"TN-2\",\"applies\":false,\"rationale\":\"Not applicable.\"}]";

        List<ComplianceSuggestion> suggestions = reader.read(envelope(content, "stop", 200), rules(2));

        assertThat(suggestions).hasSize(2);
        assertThat(suggestions.get(0).rationale()).contains("section 3[b]");
    }

    @Test
    void refusesAnAnswerWithNoArrayInItAtAll() {
        String content = "I cannot determine which compliances apply without the sanctioned plan.";

        assertThatExceptionOfType(ComplianceAiException.class)
                .isThrownBy(() -> reader.read(envelope(content, "stop", 20), rules(3)))
                .withMessageContaining("no JSON array")
                .withMessageContaining("sanctioned plan");
    }

    @Test
    void refusesAnEmptyAssistantMessage() {
        assertThatExceptionOfType(ComplianceAiException.class)
                .isThrownBy(() -> reader.read(envelope("   ", "stop", 0), rules(3)))
                .withMessageContaining("no text at all");
    }

    @Test
    void refusesAnEmptyOrUnreadableResponseBody() {
        assertThatExceptionOfType(ComplianceAiException.class)
                .isThrownBy(() -> reader.read("", rules(3)))
                .withMessageContaining("empty response body");

        assertThatExceptionOfType(ComplianceAiException.class)
                .isThrownBy(() -> reader.read("<html>502 Bad Gateway</html>", rules(3)))
                .withMessageContaining("not JSON");
    }

    private static String quote(String s) {
        try {
            return new ObjectMapper().writeValueAsString(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
