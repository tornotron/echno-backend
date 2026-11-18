package org.tornotron.echno_backend.modules.assistant.service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.assistant.AssistantModuleEnabled;
import org.tornotron.echno_backend.modules.assistant.AssistantProperties;
import org.tornotron.echno_backend.modules.assistant.api.AssistantProvider;
import org.tornotron.echno_backend.modules.assistant.api.EvidenceUnit;
import org.tornotron.echno_backend.modules.assistant.api.ProviderDescriptor;
import org.tornotron.echno_backend.modules.assistant.api.Question;
import org.tornotron.echno_backend.modules.assistant.api.Scope;
import org.tornotron.echno_backend.modules.assistant.domain.AskOutcome;
import org.tornotron.echno_backend.modules.assistant.dto.AskRequest;
import org.tornotron.echno_backend.modules.assistant.dto.AskResponse;
import org.tornotron.echno_backend.modules.assistant.dto.CostDto;
import org.tornotron.echno_backend.modules.assistant.dto.EvidenceDto;
import org.tornotron.echno_backend.modules.assistant.dto.QueryLogDto;
import org.tornotron.echno_backend.modules.assistant.dto.StatementDto;
import org.tornotron.echno_backend.modules.assistant.llm.AssistantLlmClient;
import org.tornotron.echno_backend.modules.assistant.llm.LlmResponse;
import org.tornotron.echno_backend.modules.assistant.llm.LlmUnavailableException;
import org.tornotron.echno_backend.modules.assistant.pipeline.AnswerValidator;
import org.tornotron.echno_backend.modules.assistant.pipeline.EvidenceAssembler;
import org.tornotron.echno_backend.modules.assistant.pipeline.EvidenceAssembler.Assembled;
import org.tornotron.echno_backend.modules.assistant.pipeline.EvidenceAssembler.NoteKind;
import org.tornotron.echno_backend.modules.assistant.pipeline.GroundedAnswerer;
import org.tornotron.echno_backend.modules.assistant.pipeline.Interpretation;
import org.tornotron.echno_backend.modules.assistant.pipeline.ParallelRetriever;
import org.tornotron.echno_backend.modules.assistant.pipeline.ProviderPlanner;
import org.tornotron.echno_backend.modules.assistant.pipeline.QuestionInterpreter;
import org.tornotron.echno_backend.modules.assistant.pipeline.ScopeResolver;
import org.tornotron.echno_backend.modules.assistant.pipeline.ScopeResolver.PreviousScope;
import org.tornotron.echno_backend.modules.assistant.pipeline.Statement;
import org.tornotron.echno_backend.modules.assistant.provider.AttendanceProvider;
import org.tornotron.echno_backend.modules.assistant.time.AssistantClock;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * One question, start to finish: understand it, resolve the project, pick the providers, read
 * their data as the caller, assemble the evidence, let the model write from it, check what it
 * wrote, and record the whole thing.
 *
 * <p>The ask is deliberately not one transaction. The spec's diagram draws it as one, but a
 * transaction held open across two model calls of up to 45 seconds each would pin a database
 * connection for the length of the wait, which is the shape the compliance module avoids on
 * purpose. Each read that needs the tenant filter makes its own transaction, through a
 * {@code @Transactional} service or the job runner, and the audit row is written in a short one of
 * its own before the response is returned, so there is no answer without its row.
 *
 * <p>Every ending is recorded: an answer, a question back, a decline, a degraded answer and a
 * failure all leave a row. A failure inside the pipeline is reported as {@code UNAVAILABLE} with a
 * message that names nothing internal and is logged in full, so a person gets a coherent reply
 * and not a stack trace; a refusal ({@link AccessDeniedException}) is the one thing allowed to
 * escape, because it is not a failure.
 */
@Slf4j
@Service
@AssistantModuleEnabled
public class AssistantService {

    private final AssistantLlmClient llm;
    private final QuestionInterpreter interpreter;
    private final ScopeResolver scopeResolver;
    private final ProviderPlanner planner;
    private final ParallelRetriever retriever;
    private final EvidenceAssembler assembler;
    private final GroundedAnswerer answerer;
    private final AnswerValidator validator;
    private final QueryLogService queryLog;
    private final UserContextService userContext;
    private final AssistantProperties props;
    private final Clock clock;

    public AssistantService(AssistantLlmClient llm, QuestionInterpreter interpreter, ScopeResolver scopeResolver,
                            ProviderPlanner planner, ParallelRetriever retriever, EvidenceAssembler assembler,
                            GroundedAnswerer answerer, AnswerValidator validator, QueryLogService queryLog,
                            UserContextService userContext, AssistantProperties props, @AssistantClock Clock clock) {
        this.llm = llm;
        this.interpreter = interpreter;
        this.scopeResolver = scopeResolver;
        this.planner = planner;
        this.retriever = retriever;
        this.assembler = assembler;
        this.answerer = answerer;
        this.validator = validator;
        this.queryLog = queryLog;
        this.userContext = userContext;
        this.props = props;
        this.clock = clock;
    }

    /** The caller's own questions, newest first. */
    public Page<QueryLogDto> history(int pageNo, int pageSize) {
        return queryLog.mine(currentUserId(), pageNo, pageSize);
    }

    /** Every question in the caller's organization, newest first. */
    public Page<QueryLogDto> audit(int pageNo, int pageSize) {
        return queryLog.all(pageNo, pageSize);
    }

    private Long currentUserId() {
        Long userId = userContext.getCurrentUserId();
        if (userId == null) {
            throw new AccessDeniedException("You have no account in this organization, so there is nobody to record as asking.");
        }
        return userId;
    }

    /** What a run learns on the way, kept for the audit row. */
    private static final class Run {
        final String question;
        final Long userId;
        final long startedNanos = System.nanoTime();
        final long deadlineNanos;
        Long projectId;
        String projectName;
        LocalDate from;
        LocalDate to;
        List<String> providers = List.of();
        final List<String> assumptions = new ArrayList<>();
        int promptTokens;
        int completionTokens;
        String model;
        String modelVersion;
        Map<String, Object> dropped;

        Run(String question, Long userId, long deadlineMillis) {
            this.question = question;
            this.userId = userId;
            this.deadlineNanos = startedNanos + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, deadlineMillis));
        }

        boolean expired() {
            return System.nanoTime() >= deadlineNanos;
        }

        long remainingMillis() {
            return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
        }

        void spend(LlmResponse usage) {
            promptTokens += usage.promptTokens();
            completionTokens += usage.completionTokens();
            model = usage.model();
            modelVersion = usage.modelVersion();
        }
    }

    public AskResponse ask(AskRequest request) {
        Long orgId = TenantContext.getCurrentOrgId();
        long deadlineMillis = TimeUnit.SECONDS.toMillis(Math.max(1, props.getAskDeadlineSeconds()));
        Run run = new Run(request.question().trim(), currentUserId(), deadlineMillis);
        try {
            if (!llm.isConfigured()) {
                return finish(run, AskOutcome.UNAVAILABLE, "The assistant's language model is not configured.",
                        List.of(), List.of(), List.of(), List.of(), List.of());
            }
            return pipeline(run, request, orgId);
        } catch (LlmUnavailableException e) {
            return finish(run, AskOutcome.UNAVAILABLE, e.getMessage(), List.of(), List.of(), List.of(), List.of(), List.of());
        } catch (AccessDeniedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("The assistant could not complete a question", e);
            return finish(run, AskOutcome.UNAVAILABLE, "The assistant could not complete this question. It has been recorded.",
                    List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    private AskResponse pipeline(Run run, AskRequest request, Long orgId) {
        Optional<PreviousScope> previous = request.previousQueryId() == null
                ? Optional.empty() : queryLog.previousScope(request.previousQueryId(), run.userId);

        QuestionInterpreter.Interpreted interpreted =
                interpreter.interpret(run.question, previous.map(p -> "project " + p.projectName()).orElse(null));
        run.spend(interpreted.usage());
        if (run.expired()) {
            return timedOut(run);
        }
        Interpretation reading = interpreted.interpretation();

        if (reading.multiStep()) {
            return finish(run, AskOutcome.DECLINED,
                    "I can answer one question at a time in this release. Ask the first part, then the next.",
                    List.of(), List.of(), List.of(), List.of(), List.of());
        }
        if (!reading.ambiguous().isEmpty()) {
            return clarify(run, reading.ambiguous());
        }
        if (reading.subjects().isEmpty()) {
            return clarify(run, List.of("I can answer questions about attendance and tasks. Which would you like to know about?"));
        }

        ScopeResolver.Resolution resolution = scopeResolver.resolve(reading.projectRef(), previous);
        if (resolution instanceof ScopeResolver.Resolution.Clarify clarify) {
            return clarify(run, clarify.questions());
        }
        ScopeResolver.Resolution.Resolved project = (ScopeResolver.Resolution.Resolved) resolution;
        run.projectId = project.projectId();
        run.projectName = project.projectName();
        if (project.fromPreviousQuestion()) {
            run.assumptions.add("Using the project from your previous question: " + project.projectName() + ".");
        }

        ProviderPlanner.Plan plan = planner.plan(reading.subjects());
        if (plan.selected().isEmpty()) {
            return finish(run, AskOutcome.DECLINED, "I have no source that covers that yet.",
                    List.of(), List.of(), List.of(), List.of("No source covers: " + reading.subjects()), List.of());
        }
        run.providers = plan.selected().stream().map(p -> p.describe().id()).toList();
        applyPeriod(run, reading, plan);

        if (run.from != null && run.to != null && plan.needsPeriod()) {
            long days = ChronoUnit.DAYS.between(run.from, run.to) + 1;
            if (days > AttendanceProvider.MAX_DAYS) {
                return clarify(run, List.of("That period is " + days + " days long, and I can only read up to "
                        + AttendanceProvider.MAX_DAYS + " days at a time. Which shorter date range do you want?"));
            }
        }
        if (run.expired()) {
            return timedOut(run);
        }

        List<ParallelRetriever.ProviderOutcome> outcomes = retriever.retrieve(plan.selected(),
                new Question(run.question), new Scope(run.projectId, run.from, run.to, null), orgId, run.remainingMillis());
        Assembled assembled = assembler.assemble(outcomes);
        List<String> lookedFor = assembled.notes().stream()
                .filter(n -> n.kind() != NoteKind.DENIED)
                .map(n -> n.providerId() + ": " + n.text())
                .toList();

        if (!assembled.hasEvidence()) {
            String why = !assembled.unavailable().isEmpty() && assembled.unavailable().size() == assembled.notes().size()
                    ? "I could not read the sources I needed, so I cannot answer that."
                    : "I looked and found nothing recorded that answers that.";
            return finish(run, AskOutcome.DECLINED, why, List.of(), List.of(), List.of(), lookedFor, List.of());
        }
        if (run.expired()) {
            return timedOut(run);
        }

        List<ProviderDescriptor> descriptors = plan.selected().stream().map(AssistantProvider::describe).toList();
        GroundedAnswerer.Generated generated = answerer.answer(run.question, scopeSentence(run), run.assumptions,
                assembled.units(), descriptors);
        run.spend(generated.usage());
        AnswerValidator.Validated checked = validator.validate(generated.statements(), assembled.units(), descriptors);
        run.dropped = Map.of("uncited", checked.droppedUncited(), "foreign", checked.droppedForeign(),
                "numbers", checked.droppedNumbers());

        if (generated.insufficient() || !checked.hasFacts()) {
            return finish(run, AskOutcome.DECLINED, "I could not give an answer that the records support.",
                    List.of(), List.of(), List.of(), lookedFor, List.of());
        }

        if (checked.dropped() > 0) {
            String noun = checked.dropped() == 1 ? "statement was" : "statements were";
            run.assumptions.add(checked.dropped() + " unsupported " + noun + " omitted from this answer.");
        }

        List<Statement> shown = new ArrayList<>(checked.facts());
        shown.addAll(checked.reasoning());
        Set<String> cited = new LinkedHashSet<>();
        shown.forEach(s -> cited.addAll(s.evidenceIds()));
        List<EvidenceDto> evidence = assembled.units().stream().filter(u -> cited.contains(u.id())).map(AssistantService::dto).toList();
        List<String> unavailable = assembled.unavailable().stream().map(n -> n.providerId() + ": " + n.text()).toList();
        AskOutcome outcome = unavailable.isEmpty() ? AskOutcome.ANSWERED : AskOutcome.DEGRADED;
        String message;
        if (unavailable.isEmpty()) {
            message = checked.dropped() > 0
                    ? "Here is what the records support; some unsupported parts of the draft answer were omitted."
                    : "Here is what the records show.";
        } else {
            message = checked.dropped() > 0
                    ? "Here is what the records support, but some sources could not be read and some unsupported parts of the draft answer were omitted."
                    : "Here is what the records show, but some sources could not be read.";
        }
        return finish(run, outcome, message,
                shown.stream().map(s -> new StatementDto(s.text(), s.kind().name(), s.evidenceIds())).toList(),
                evidence, List.of(), lookedFor, unavailable);
    }

    private AskResponse timedOut(Run run) {
        return finish(run, AskOutcome.UNAVAILABLE, "This question took too long to answer. Try again in a moment.",
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /**
     * A period the person gave is used as given. Without one, a provider that answers only for a
     * period gets the current week so far, and the answer says so; one that does not gets none, and
     * the answer says it reflects today.
     */
    private void applyPeriod(Run run, Interpretation reading, ProviderPlanner.Plan plan) {
        LocalDate today = LocalDate.now(clock);
        if (reading.hasPeriod()) {
            run.from = reading.from();
            run.to = reading.to();
        } else if (plan.needsPeriod()) {
            run.from = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            run.to = today;
            run.assumptions.add("No period was given, so I used this week so far: " + run.from + " to " + run.to + ".");
        } else {
            run.assumptions.add("No period was given, so this reflects the current state as of " + today + ".");
        }
    }

    private AskResponse clarify(Run run, List<String> questions) {
        return finish(run, AskOutcome.CLARIFY, "I need a little more to answer that.",
                List.of(), List.of(), questions, List.of(), List.of());
    }

    private static String scopeSentence(Run run) {
        String period = run.from == null ? "as of today" : run.from + " to " + run.to;
        return "project " + run.projectName + " (id " + run.projectId + "), " + period;
    }

    private static EvidenceDto dto(EvidenceUnit unit) {
        return new EvidenceDto(unit.id(), unit.providerId(), unit.kind(), unit.values(), unit.source());
    }

    /** Writes the audit row, then builds the response around its id. */
    private AskResponse finish(Run run, AskOutcome outcome, String message, List<StatementDto> statements,
                               List<EvidenceDto> evidence, List<String> clarification, List<String> lookedFor,
                               List<String> unavailable) {
        long micros = run.promptTokens * props.getCost().getPromptTokenMicros()
                + run.completionTokens * props.getCost().getCompletionTokenMicros();

        Map<String, Object> scope = null;
        if (run.projectId != null) {
            scope = new LinkedHashMap<>();
            scope.put("projectId", run.projectId);
            scope.put("projectName", run.projectName);
            scope.put("from", run.from == null ? null : run.from.toString());
            scope.put("to", run.to == null ? null : run.to.toString());
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("message", message);
        answer.put("statements", statements);
        answer.put("clarification", clarification);
        answer.put("lookedFor", lookedFor);
        answer.put("assumptions", run.assumptions);
        answer.put("unavailableSources", unavailable);
        if (run.dropped != null) {
            answer.put("dropped", run.dropped);
        }

        long latencyMs = (System.nanoTime() - run.startedNanos) / 1_000_000;
        UUID queryId = queryLog.record(new QueryLogService.Entry(run.projectId, run.userId, run.question, scope,
                run.providers, evidence.stream().map(EvidenceDto::id).toList(), outcome, answer,
                run.model, run.modelVersion, run.promptTokens, run.completionTokens, micros, latencyMs));
        return new AskResponse(queryId, outcome, message, statements, evidence, clarification, lookedFor,
                List.copyOf(run.assumptions), unavailable, new CostDto(run.promptTokens, run.completionTokens, micros));
    }
}
