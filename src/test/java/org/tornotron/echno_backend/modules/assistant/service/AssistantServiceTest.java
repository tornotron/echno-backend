package org.tornotron.echno_backend.modules.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.common.retry.TransactionalWorkRunner;
import org.tornotron.echno_backend.modules.assistant.AssistantProperties;
import org.tornotron.echno_backend.modules.assistant.api.AssistantProvider;
import org.tornotron.echno_backend.modules.assistant.api.CostProfile;
import org.tornotron.echno_backend.modules.assistant.api.EvidenceUnit;
import org.tornotron.echno_backend.modules.assistant.api.FieldSpec;
import org.tornotron.echno_backend.modules.assistant.api.ProviderDescriptor;
import org.tornotron.echno_backend.modules.assistant.api.ProviderResult;
import org.tornotron.echno_backend.modules.assistant.api.Question;
import org.tornotron.echno_backend.modules.assistant.api.Scope;
import org.tornotron.echno_backend.modules.assistant.api.SourceRef;
import org.tornotron.echno_backend.modules.assistant.api.Subject;
import org.tornotron.echno_backend.modules.assistant.domain.AskOutcome;
import org.tornotron.echno_backend.modules.assistant.dto.AskRequest;
import org.tornotron.echno_backend.modules.assistant.dto.AskResponse;
import org.tornotron.echno_backend.modules.assistant.llm.FakeLlmClient;
import org.tornotron.echno_backend.modules.assistant.llm.LlmUnavailableException;
import org.tornotron.echno_backend.modules.assistant.pipeline.AnswerValidator;
import org.tornotron.echno_backend.modules.assistant.pipeline.EvidenceAssembler;
import org.tornotron.echno_backend.modules.assistant.pipeline.GroundedAnswerer;
import org.tornotron.echno_backend.modules.assistant.pipeline.ParallelRetriever;
import org.tornotron.echno_backend.modules.assistant.pipeline.ProviderPlanner;
import org.tornotron.echno_backend.modules.assistant.pipeline.QuestionInterpreter;
import org.tornotron.echno_backend.modules.assistant.pipeline.ScopeResolver;
import org.tornotron.echno_backend.modules.assistant.pipeline.ScopeResolver.PreviousScope;
import org.tornotron.echno_backend.modules.assistant.provider.ProviderRegistry;
import org.tornotron.echno_backend.project.ProjectService;
import org.tornotron.echno_backend.project.dto.ProjectSummaryDto;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * The whole pipeline against the fake model, with the providers and the project lookup stubbed:
 * one test for each way a question can end, and for each, that exactly one audit row was written.
 * This is the scripted walk of the stage gate (an answer with citations, a clarification, a
 * decline that says what was looked for, a degraded answer) made repeatable.
 */
class AssistantServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T04:00:00Z"), ZoneId.of("Asia/Kolkata"));
    private static final String ASK = "How many workers were on site at Tower B this week?";
    private static final String ASK_BOTH = "Attendance and tasks at Tower B";

    private final FakeLlmClient fake = new FakeLlmClient();
    private final ProjectService projects = mock(ProjectService.class);
    private final QueryLogService queryLog = mock(QueryLogService.class);
    private final UserContextService userContext = mock(UserContextService.class);
    private final AssistantProperties props = new AssistantProperties();

    private StubProvider attendance;
    private StubProvider task;
    private AssistantService service;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(7L);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("caller", null, List.of()));
        when(userContext.getCurrentUserId()).thenReturn(11L);
        when(queryLog.record(any())).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        props.getRetrieval().setProviderTimeoutSeconds(2);
        projectNamed("Tower B", 42L, "Tower B");

        attendance = new StubProvider("attendance", Subject.ATTENDANCE, true, () -> evidence("attendance", "attendance:2026-10-05:project-42",
                Map.of("date", "2026-10-05", "projectId", 42, "recordCount", 12)));
        task = new StubProvider("task", Subject.TASK, false, () -> evidence("task", "task:7",
                Map.of("taskId", 7, "status", "onGoing", "progress", 40.0)));
        service = build(attendance, task);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        fake.reset();
    }

    // ---- an answer with citations

    @Test
    void anAnswerCitesTheEvidenceItRestsOnAndStatesThePeriodItAssumed() {
        AskResponse response = service.ask(new AskRequest(ASK, null));

        assertThat(response.outcome()).isEqualTo(AskOutcome.ANSWERED);
        assertThat(response.statements()).isNotEmpty().allSatisfy(s -> {
            assertThat(s.kind()).isEqualTo("FACT");
            assertThat(s.evidenceIds()).containsExactly("attendance:2026-10-05:project-42");
        });
        assertThat(response.evidence()).extracting(e -> e.id()).containsExactly("attendance:2026-10-05:project-42");
        assertThat(response.assumptions()).singleElement().asString()
                .contains("this week so far").contains("2026-10-05").contains("2026-10-07");
        assertThat(attendance.lastScope.projectId()).isEqualTo(42L);
        assertThat(attendance.lastScope.from()).hasToString("2026-10-05");
        assertThat(attendance.lastScope.to()).hasToString("2026-10-07");
        assertThat(task.calls.get()).as("the task provider is not asked about attendance").isZero();
        assertThat(entry().outcome()).isEqualTo(AskOutcome.ANSWERED);
        assertThat(entry().providersSelected()).containsExactly("attendance");
        assertThat(entry().evidenceIds()).containsExactly("attendance:2026-10-05:project-42");
        assertThat(response.queryId()).isNotNull();
    }

    @Test
    void aPeriodTheQuestionGivesIsUsedAsGivenAndNoDefaultIsClaimed() {
        AskResponse response = service.ask(new AskRequest("Attendance at Tower B from 2026-10-01 to 2026-10-03", null));

        assertThat(response.assumptions()).isEmpty();
        assertThat(attendance.lastScope.from()).hasToString("2026-10-01");
        assertThat(attendance.lastScope.to()).hasToString("2026-10-03");
    }

    @Test
    void aSubjectWithNoPeriodSaysItReflectsToday() {
        AskResponse response = service.ask(new AskRequest("Which tasks are overdue at Tower B?", null));

        assertThat(response.assumptions()).singleElement().asString().contains("current state").contains("2026-10-07");
        assertThat(task.lastScope.from()).isNull();
    }

    @Test
    void theAnswerCarriesWhatTheModelCallsCost() {
        props.getCost().setPromptTokenMicros(2);
        props.getCost().setCompletionTokenMicros(3);

        AskResponse response = service.ask(new AskRequest(ASK, null));

        assertThat(response.cost().promptTokens()).isPositive();
        assertThat(response.cost().estimatedCostMicros())
                .isEqualTo(response.cost().promptTokens() * 2L + response.cost().completionTokens() * 3L);
        assertThat(entry().promptTokens()).isEqualTo(response.cost().promptTokens());
        assertThat(entry().llmModel()).isEqualTo("fake");
    }

    // ---- a clarification

    @Test
    void anAmbiguousProjectIsAskedBackAndNothingIsRead() {
        when(projects.getProjectsSummaryPaginated(0, 50, "Tower")).thenReturn(new PageImpl<>(List.of(
                project(1L, "Tower A"), project(2L, "Tower B"), project(3L, "Tower C"))));

        AskResponse response = service.ask(new AskRequest("How many workers were on site at Tower this week?", null));

        assertThat(response.outcome()).isEqualTo(AskOutcome.CLARIFY);
        assertThat(response.clarification()).singleElement().asString().contains("Tower A").contains("Tower B").contains("Tower C");
        assertThat(response.statements()).isEmpty();
        assertThat(attendance.calls.get() + task.calls.get()).isZero();
        assertOneRowWith(AskOutcome.CLARIFY);
    }

    @Test
    void aProjectTheCallerCannotListIsAskedBackAsNotFoundNeverGuessed() {
        when(projects.getProjectsSummaryPaginated(0, 50, "Secret Site")).thenReturn(new PageImpl<>(List.of()));

        AskResponse response = service.ask(new AskRequest("Attendance at Secret Site", null));

        assertThat(response.outcome()).isEqualTo(AskOutcome.CLARIFY);
        assertThat(response.clarification()).singleElement().asString().contains("Secret Site").contains("could not find");
        assertThat(attendance.calls.get()).isZero();
    }

    @Test
    void aQuestionWithNoProjectAsksWhichOne() {
        AskResponse response = service.ask(new AskRequest("How many workers are present today?", null));

        assertThat(response.outcome()).isEqualTo(AskOutcome.CLARIFY);
        assertThat(response.clarification()).containsExactly("Which project do you mean?");
    }

    @Test
    void aQuestionAboutNothingItKnowsIsAskedBack() {
        AskResponse response = service.ask(new AskRequest("What is the meaning of life at Tower B?", null));

        assertThat(response.outcome()).isEqualTo(AskOutcome.CLARIFY);
        assertThat(attendance.calls.get() + task.calls.get()).isZero();
    }

    @Test
    void aFollowUpLeansOnTheCallersPreviousProjectAndSaysSo() {
        UUID previous = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        when(queryLog.previousScope(previous, 11L)).thenReturn(Optional.of(new PreviousScope(42L, "Tower B")));

        AskResponse response = service.ask(new AskRequest("And which tasks are overdue?", previous));

        assertThat(response.outcome()).isEqualTo(AskOutcome.ANSWERED);
        assertThat(response.assumptions()).anyMatch(a -> a.contains("previous question") && a.contains("Tower B"));
        assertThat(task.lastScope.projectId()).isEqualTo(42L);
    }

    @Test
    void aFollowUpThatNamesItsOwnProjectIgnoresTheOldOne() {
        UUID previous = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        when(queryLog.previousScope(previous, 11L)).thenReturn(Optional.of(new PreviousScope(99L, "Old Site")));

        service.ask(new AskRequest(ASK, previous));

        assertThat(attendance.lastScope.projectId()).isEqualTo(42L);
    }

    @Test
    void aPreviousQueryThatIsNotTheCallersOrIsTooOldGivesNoScope() {
        UUID previous = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        when(queryLog.previousScope(previous, 11L)).thenReturn(Optional.empty());

        AskResponse response = service.ask(new AskRequest("And which tasks are overdue?", previous));

        assertThat(response.outcome()).isEqualTo(AskOutcome.CLARIFY);
    }

    // ---- a decline that names what was looked for

    @Test
    void whenNothingIsRecordedTheDeclineNamesWhatWasLookedFor() {
        attendance.returns(() -> new ProviderResult.Empty("attendance records for project 42 from 2026-10-05 to 2026-10-07"));

        AskResponse response = service.ask(new AskRequest(ASK, null));

        assertThat(response.outcome()).isEqualTo(AskOutcome.DECLINED);
        assertThat(response.statements()).isEmpty();
        assertThat(response.lookedFor()).singleElement().asString()
                .contains("attendance").contains("project 42").contains("2026-10-05");
        assertThat(response.message()).contains("found nothing");
        assertOneRowWith(AskOutcome.DECLINED);
    }

    @Test
    void whenEverySourceCouldNotBeReadTheDeclineSaysThatAndNotThatNothingExists() {
        attendance.returns(() -> new ProviderResult.Unavailable("The attendance source could not be read."));

        AskResponse response = service.ask(new AskRequest(ASK, null));

        assertThat(response.outcome()).isEqualTo(AskOutcome.DECLINED);
        assertThat(response.message()).contains("could not read");
        assertThat(response.message()).doesNotContain("found nothing");
    }

    @Test
    void aTwoPartQuestionIsDeclinedNotHalfAnswered() {
        AskResponse response = service.ask(new AskRequest("Compare attendance at Tower B and then list its tasks", null));

        assertThat(response.outcome()).isEqualTo(AskOutcome.DECLINED);
        assertThat(response.message()).contains("one question at a time");
        assertThat(attendance.calls.get() + task.calls.get()).isZero();
        assertOneRowWith(AskOutcome.DECLINED);
    }

    @Test
    void aSubjectNoSourceCoversIsDeclinedAndSaysSo() {
        fake.whenInterpreting("How much cement is left at Tower B?",
                "{\"subjects\":[\"MATERIAL\"],\"projectRef\":\"Tower B\",\"ambiguous\":[]}");

        AskResponse response = service.ask(new AskRequest("How much cement is left at Tower B?", null));

        assertThat(response.outcome()).isEqualTo(AskOutcome.DECLINED);
        assertThat(response.lookedFor()).singleElement().asString().contains("MATERIAL");
    }

    // ---- a degraded answer

    @Test
    void aProviderThatThrowsGivesADegradedAnswerThatNamesIt() {
        task.returns(() -> {
            throw new IllegalStateException("jdbc:cockroach://secret-host exploded");
        });

        AskResponse response = service.ask(new AskRequest(ASK_BOTH, null));

        assertThat(response.outcome()).isEqualTo(AskOutcome.DEGRADED);
        assertThat(response.statements()).isNotEmpty();
        assertThat(response.unavailableSources()).singleElement().asString().contains("task").doesNotContain("jdbc");
        assertThat(response.evidence()).extracting(e -> e.providerId()).containsOnly("attendance");
        assertOneRowWith(AskOutcome.DEGRADED);
    }

    @Test
    void aProviderThatRefusesTheCallerIsLeftOutQuietlyAndNeverCountsAsASourceFailure() {
        task.returns(() -> {
            throw new AccessDeniedException("Access Denied");
        });

        AskResponse response = service.ask(new AskRequest(ASK_BOTH, null));

        assertThat(response.outcome()).isEqualTo(AskOutcome.ANSWERED);
        assertThat(response.unavailableSources()).isEmpty();
        assertThat(response.evidence()).extracting(e -> e.providerId()).containsOnly("attendance");
    }

    @Test
    void anAttendancePeriodLongerThanThirtyOneDaysAsksForClarification() {
        AskResponse response = service.ask(new AskRequest("Attendance at Tower B from 2026-08-01 to 2026-10-01", null));

        assertThat(response.outcome()).isEqualTo(AskOutcome.CLARIFY);
        assertThat(response.clarification()).singleElement().asString().contains("31 days");
        assertThat(attendance.calls.get()).isZero();
        assertOneRowWith(AskOutcome.CLARIFY);
    }

    @Test
    void aQuestionThatExceedsItsWholeAskDeadlineReturnsUnavailable() {
        props.setAskDeadlineSeconds(1);
        AtomicInteger llmCalls = new AtomicInteger();
        var slowLlm = new org.tornotron.echno_backend.modules.assistant.llm.AssistantLlmClient() {
            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public org.tornotron.echno_backend.modules.assistant.llm.LlmResponse chat(String system, String user) {
                llmCalls.incrementAndGet();
                try {
                    Thread.sleep(1_100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return fake.chat(system, user);
            }
        };

        AskResponse response = build(slowLlm, attendance, task).ask(new AskRequest(ASK, null));

        assertThat(response.outcome()).isEqualTo(AskOutcome.UNAVAILABLE);
        assertThat(response.message()).contains("too long");
        assertThat(llmCalls.get()).isEqualTo(1);
        assertThat(attendance.calls.get()).isZero();
        assertOneRowWith(AskOutcome.UNAVAILABLE);
    }

    @Test
    void aProviderThatSaysItFoundNothingDoesNotDegradeTheAnswer() {
        task.returns(() -> new ProviderResult.Empty("tasks of project 42"));

        AskResponse response = service.ask(new AskRequest(ASK_BOTH, null));

        assertThat(response.outcome()).isEqualTo(AskOutcome.ANSWERED);
        assertThat(response.unavailableSources()).isEmpty();
        assertThat(response.lookedFor()).singleElement().asString().contains("task");
    }

    // ---- the model

    @Test
    void anUnreachableModelIsUnavailableAndStillAudited() {
        fake.failWith(new LlmUnavailableException("The assistant's language model could not be reached. Try again in a moment."));

        AskResponse response = service.ask(new AskRequest(ASK, null));

        assertThat(response.outcome()).isEqualTo(AskOutcome.UNAVAILABLE);
        assertThat(response.message()).contains("could not be reached");
        assertOneRowWith(AskOutcome.UNAVAILABLE);
    }

    @Test
    void aModelThatIsNotConfiguredIsUnavailableBeforeAnythingIsAsked() {
        var unconfigured = new org.tornotron.echno_backend.modules.assistant.llm.AssistantLlmClient() {
            @Override
            public boolean isConfigured() {
                return false;
            }

            @Override
            public org.tornotron.echno_backend.modules.assistant.llm.LlmResponse chat(String system, String user) {
                throw new AssertionError("an unconfigured model must not be called");
            }
        };

        AskResponse response = build(unconfigured, attendance, task).ask(new AskRequest(ASK, null));

        assertThat(response.outcome()).isEqualTo(AskOutcome.UNAVAILABLE);
        assertThat(response.message()).contains("not configured");
        assertOneRowWith(AskOutcome.UNAVAILABLE);
    }

    // ---- what the validator does to what the model says

    @Test
    void aFactWithNoCitationLeavesNothingToAnswerWithAndIsDeclined() {
        fake.whenAnswering(ASK, "{\"statements\":[{\"text\":\"12 workers were on site.\",\"kind\":\"fact\",\"evidenceIds\":[]}],"
                + "\"insufficient\":false}");

        AskResponse response = service.ask(new AskRequest(ASK, null));

        assertThat(response.outcome()).isEqualTo(AskOutcome.DECLINED);
        assertThat(response.statements()).isEmpty();
        assertThat(entry().answer().get("dropped")).isEqualTo(Map.of("uncited", 1, "foreign", 0, "numbers", 0));
    }

    @Test
    void aFactCitingEvidenceFromNowhereIsDeclined() {
        fake.whenAnswering(ASK, "{\"statements\":[{\"text\":\"30 workers were on site.\",\"kind\":\"fact\","
                + "\"evidenceIds\":[\"attendance:2026-10-05:project-99\"]}],\"insufficient\":false}");

        AskResponse response = service.ask(new AskRequest(ASK, null));

        assertThat(response.outcome()).isEqualTo(AskOutcome.DECLINED);
        assertThat(entry().answer().get("dropped")).isEqualTo(Map.of("uncited", 0, "foreign", 1, "numbers", 0));
    }

    @Test
    void aNumberTheEvidenceDoesNotHoldIsDeclined() {
        fake.whenAnswering(ASK, "{\"statements\":[{\"text\":\"15 workers were on site.\",\"kind\":\"fact\","
                + "\"evidenceIds\":[\"attendance:2026-10-05:project-42\"]}],\"insufficient\":false}");

        AskResponse response = service.ask(new AskRequest(ASK, null));

        assertThat(response.outcome()).isEqualTo(AskOutcome.DECLINED);
        assertThat(entry().answer().get("dropped")).isEqualTo(Map.of("uncited", 0, "foreign", 0, "numbers", 1));
    }

    @Test
    void oneGoodFactBesideBadOnesIsAnsweredWithOnlyTheGoodOneAndTellsThePersonSomethingWasOmitted() {
        fake.whenAnswering(ASK, "{\"statements\":["
                + "{\"text\":\"15 workers were on site.\",\"kind\":\"fact\",\"evidenceIds\":[\"attendance:2026-10-05:project-42\"]},"
                + "{\"text\":\"12 workers have a record on 2026-10-05.\",\"kind\":\"fact\",\"evidenceIds\":[\"attendance:2026-10-05:project-42\"]},"
                + "{\"text\":\"It looks like a normal day.\",\"kind\":\"inference\",\"evidenceIds\":[\"attendance:2026-10-05:project-42\"]}"
                + "],\"insufficient\":false}");

        AskResponse response = service.ask(new AskRequest(ASK, null));

        assertThat(response.outcome()).isEqualTo(AskOutcome.ANSWERED);
        assertThat(response.statements()).extracting(s -> s.kind()).containsExactly("FACT", "INFERENCE");
        assertThat(response.statements().get(0).text()).startsWith("12 workers");
        assertThat(response.message()).contains("omitted");
        assertThat(response.assumptions()).anyMatch(a -> a.contains("1 unsupported statement was omitted"));
    }

    @Test
    void aModelThatSaysTheEvidenceIsInsufficientIsDeclined() {
        fake.whenAnswering(ASK, "{\"statements\":[],\"insufficient\":true}");

        assertThat(service.ask(new AskRequest(ASK, null)).outcome()).isEqualTo(AskOutcome.DECLINED);
    }

    @Test
    void aModelThatMarksInsufficientTrueIsDeclinedEvenWhenItAlsoReturnsStatements() {
        fake.whenAnswering(ASK, "{\"statements\":["
                + "{\"text\":\"12 workers have a record on 2026-10-05.\",\"kind\":\"fact\",\"evidenceIds\":[\"attendance:2026-10-05:project-42\"]}"
                + "],\"insufficient\":true}");

        AskResponse response = service.ask(new AskRequest(ASK, null));

        assertThat(response.outcome()).isEqualTo(AskOutcome.DECLINED);
        assertThat(response.statements()).isEmpty();
    }

    @Test
    void aModelReplyThatIsNotJsonIsDeclinedNotAnError() {
        fake.whenAnswering(ASK, "Sorry, I cannot help with that.");

        assertThat(service.ask(new AskRequest(ASK, null)).outcome()).isEqualTo(AskOutcome.DECLINED);
    }

    // ---- failures

    @Test
    void anUnexpectedFailureInsideThePipelineIsAReplyAndARowNotAStackTrace() {
        when(projects.getProjectsSummaryPaginated(0, 50, "Tower B")).thenThrow(new IllegalStateException("jdbc:cockroach://secret-host"));

        AskResponse response = service.ask(new AskRequest(ASK, null));

        assertThat(response.outcome()).isEqualTo(AskOutcome.UNAVAILABLE);
        assertThat(response.message()).doesNotContain("jdbc").doesNotContain("secret-host");
        assertOneRowWith(AskOutcome.UNAVAILABLE);
    }

    @Test
    void aRefusalIsNotAFailureAndIsNotSwallowed() {
        when(projects.getProjectsSummaryPaginated(0, 50, "Tower B")).thenThrow(new AccessDeniedException("no"));

        assertThatThrownBy(() -> service.ask(new AskRequest(ASK, null))).isInstanceOf(AccessDeniedException.class);
        verify(queryLog, never()).record(any());
    }

    @Test
    void aCallerWithNoAccountIsRefusedBeforeAnythingHappens() {
        when(userContext.getCurrentUserId()).thenReturn(null);

        assertThatThrownBy(() -> service.ask(new AskRequest(ASK, null))).isInstanceOf(AccessDeniedException.class);
        verify(queryLog, never()).record(any());
        assertThat(attendance.calls.get()).isZero();
    }

    @Test
    void theAuditRowRecordsWhoAskedWhatAndHowLong() {
        service.ask(new AskRequest("  " + ASK + "  ", null));

        QueryLogService.Entry entry = entry();
        assertThat(entry.userId()).isEqualTo(11L);
        assertThat(entry.question()).isEqualTo(ASK);
        assertThat(entry.projectId()).isEqualTo(42L);
        assertThat(entry.resolvedScope()).containsEntry("projectName", "Tower B").containsEntry("from", "2026-10-05");
        assertThat(entry.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    // ---- helpers

    private AssistantService build(StubProvider... providers) {
        return build(fake, providers);
    }

    private AssistantService build(org.tornotron.echno_backend.modules.assistant.llm.AssistantLlmClient llm,
                                   StubProvider... providers) {
        TenantScopedJobRunner runner = new TenantScopedJobRunner(new TransactionalWorkRunner() {
            @Override
            public <T> T runInTransaction(Supplier<T> work) {
                return work.get();
            }
        });
        return new AssistantService(llm, new QuestionInterpreter(llm, CLOCK), new ScopeResolver(projects),
                new ProviderPlanner(new ProviderRegistry(List.<AssistantProvider>of(providers))),
                new ParallelRetriever(runner, props), new EvidenceAssembler(props), new GroundedAnswerer(llm),
                new AnswerValidator(), queryLog, userContext, props, CLOCK);
    }

    private void projectNamed(String search, Long id, String name) {
        when(projects.getProjectsSummaryPaginated(0, 50, search)).thenReturn(new PageImpl<>(List.of(project(id, name))));
    }

    private static ProjectSummaryDto project(Long id, String name) {
        ProjectSummaryDto dto = new ProjectSummaryDto();
        dto.setId(id);
        dto.setProjectName(name);
        return dto;
    }

    private static ProviderResult evidence(String provider, String id, Map<String, Object> values) {
        return new ProviderResult.Evidence(List.of(new EvidenceUnit(id, provider, provider + "-unit", values,
                SourceRef.entity(provider, id), 1.0)));
    }

    private QueryLogService.Entry entry() {
        ArgumentCaptor<QueryLogService.Entry> captor = ArgumentCaptor.forClass(QueryLogService.Entry.class);
        verify(queryLog, times(1)).record(captor.capture());
        return captor.getValue();
    }

    private void assertOneRowWith(AskOutcome outcome) {
        assertThat(entry().outcome()).isEqualTo(outcome);
    }

    /** A provider that records how it was called and returns whatever it is told to. */
    private static final class StubProvider implements AssistantProvider {
        final AtomicInteger calls = new AtomicInteger();
        volatile Scope lastScope;
        private final ProviderDescriptor descriptor;
        private volatile Supplier<ProviderResult> body;

        StubProvider(String id, Subject subject, boolean needsPeriod, Supplier<ProviderResult> body) {
            this.descriptor = new ProviderDescriptor(id, "answers things",
                    List.of(new FieldSpec("value", "units", "a value")), Set.of(subject), needsPeriod, false, new CostProfile(1, 0));
            this.body = body;
        }

        void returns(Supplier<ProviderResult> body) {
            this.body = body;
        }

        @Override
        public ProviderDescriptor describe() {
            return descriptor;
        }

        @Override
        public ProviderResult retrieve(Question question, Scope scope) {
            calls.incrementAndGet();
            lastScope = scope;
            return body.get();
        }
    }
}
