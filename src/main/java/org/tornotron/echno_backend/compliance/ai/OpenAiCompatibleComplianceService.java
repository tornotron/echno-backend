package org.tornotron.echno_backend.compliance.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.tornotron.echno_backend.common.exception.ComplianceAiException;
import org.tornotron.echno_backend.compliance.ComplianceGenerationProgress;
import org.tornotron.echno_backend.compliance.domain.ComplianceRule;
import org.tornotron.echno_backend.project.Project;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wraps the OpenAI-compatible chat-completions call that decides which candidate
 * compliance rules apply to a project. The endpoint is any service speaking the
 * OpenAI Chat Completions schema (DigitalOcean Gradient serverless inference by
 * default, or OpenAI, or a self-hosted OSS-model gateway), selected purely through
 * {@code compliance.ai.*} config.
 *
 * <h2>What an empty result means, and what it no longer means</h2>
 *
 * <p>The service stays fail-soft about being switched off: with the AI disabled, with no
 * API key, or with no candidate rules it returns an empty list and the caller generates
 * nothing. Those are configuration states rather than failures, and they must not break
 * project approval.
 *
 * <p>Every other outcome now raises {@link ComplianceAiException}. An empty list used to
 * stand for the call failing, the response being cut short by the token cap and the JSON
 * failing to parse as well, so the worst outcome available (the rule catalogue outgrows
 * the token budget, and from then on every run produces nothing) reached the user as a
 * successful run in which no compliances happened to apply. A model that assessed every
 * rule and found none applicable answers with one element per rule carrying
 * {@code applies: false}, never with an empty array, so nothing legitimate is lost by
 * treating an empty or short answer as a failure.
 *
 * <p>{@link ComplianceResponseReader} holds the checks that decide this, and its javadoc
 * explains how a truncated answer is recognised from the response alone.
 *
 * <h2>Why the rules are asked about a batch at a time</h2>
 *
 * <p>One call for the whole catalogue does not scale, and both of its limits have now been
 * measured against the configured endpoint rather than estimated. The answer costs about 57
 * completion tokens per rule (53 to 59 across runs of 6 to 60 rules), so a single call runs
 * out of the 4096-token budget at about 72 rules, which a 72-rule run confirmed by coming
 * back with {@code finish_reason: length}, exactly 4096 completion tokens and an array that
 * never closes. Wall clock is the tighter of the two: about 1.7 seconds a rule, so 40 rules
 * already takes 58 seconds and 60 takes 108.
 *
 * <p>Splitting the catalogue into batches removes both. Each call asks about a fixed number
 * of rules, so per-call output and per-call wall clock stop growing with the catalogue and
 * the whole thing scales by doing more calls rather than one bigger one. It also gives the
 * run something to report between calls, which is what makes progress on a queued job real
 * rather than a guess.
 *
 * <p>The batches run concurrently, which was worth checking before relying on: four
 * simultaneous ten-rule calls against the configured endpoint finished in 22 seconds, where
 * the same four run one after another took 91. So the endpoint does not queue calls behind a
 * single key, and the whole run costs roughly one batch rather than the sum of them. Without
 * that measurement the sensible default would have been to run them one at a time, since
 * concurrency against a serialising endpoint buys nothing and only makes the failure modes
 * harder.
 *
 * <p>The batch size is configuration, and the default of ten is chosen from those numbers
 * with room on both sides: ten rules costs about 600 completion tokens, under a sixth of the
 * budget, and takes about 20 seconds, a third of the read timeout. A batch of twenty would
 * still fit the token budget but would sit at roughly two thirds of the read timeout, which
 * is not enough margin for one slow call.
 *
 * <h2>What happens when one batch fails</h2>
 *
 * <p>The whole call fails and nothing is returned. That is not a shortcut around combining
 * partial results; it is the same rule the truncation checks exist to enforce, applied one
 * level up. A run that assessed batches one and two and lost batch three has no opinion at
 * all about the rules in batches three to five, and a caller handed the first two batches
 * would create compliances from them and show the user a finished result that quietly omits
 * two fifths of the jurisdiction. Missing compliances that nobody knows are missing is the
 * failure this module exists to prevent, so an incomplete run reports itself as a failure
 * and creates nothing. Re-running is cheap and idempotent, which is what makes that
 * affordable.
 */
@Slf4j
@Service
public class OpenAiCompatibleComplianceService {

    private final ComplianceAiProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ComplianceResponseReader responseReader;

    public OpenAiCompatibleComplianceService(ComplianceAiProperties props) {
        this.props = props;
        this.responseReader = new ComplianceResponseReader(objectMapper, props.getMaxTokens());
    }

    /**
     * Whether the AI service is switched on and has an API key, decided purely from
     * config with no network call. When this is false the service always no-ops, so
     * callers can tell "AI not configured" apart from "AI ran and found nothing".
     */
    public boolean isConfigured() {
        return props.isEnabled() && props.getApiKey() != null && !props.getApiKey().isBlank();
    }

    /**
     * Asks the model which of the candidate rules apply to the project, returning one
     * suggestion per candidate rule.
     *
     * <p>Returns an empty list only when there was nothing to ask: the AI is disabled or
     * unconfigured, or there are no candidate rules.
     *
     * @throws ComplianceAiException when a call failed, or an answer was cut short,
     *                               unparseable, or did not cover every rule it was sent
     */
    public List<ComplianceSuggestion> suggestCompliances(Project project,
                                                         String state,
                                                         List<ComplianceRule> candidateRules) {
        return suggestCompliances(project, state, candidateRules, ComplianceGenerationProgress.NONE);
    }

    /**
     * As {@link #suggestCompliances(Project, String, List)}, reporting each finished batch to
     * {@code progress}.
     *
     * <p>The batches partition the candidate rules, so every rule is in exactly one batch and
     * each batch's answer is checked for full coverage of its own rules before it is
     * accepted. Coverage of the whole run therefore follows from the batches, and there is no
     * separate whole-run check that could disagree with the per-batch ones.
     */
    public List<ComplianceSuggestion> suggestCompliances(Project project,
                                                         String state,
                                                         List<ComplianceRule> candidateRules,
                                                         ComplianceGenerationProgress progress) {
        if (!isConfigured()) {
            log.info("Compliance AI disabled or API key not configured; skipping AI suggestion for project {}",
                    project.getId());
            return List.of();
        }
        if (candidateRules == null || candidateRules.isEmpty()) {
            return List.of();
        }

        List<List<ComplianceRule>> batches = batch(candidateRules, props.getBatchSize());
        if (batches.size() == 1) {
            return runBatches(project, state, candidateRules, batches, progress, null);
        }

        // One pool per run rather than a shared bean. A run lasts tens of seconds and there is
        // at most one per project, so the cost of creating threads is nothing against the cost
        // of the calls they make, and in exchange an idle application holds no threads open and
        // one tenant's oversized catalogue cannot starve another tenant's run of a shared pool.
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(effectiveConcurrency(), batches.size()), batchThreadFactory());
        try {
            return runBatches(project, state, candidateRules, batches, progress, pool);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Runs the batches and concatenates their answers in batch order.
     *
     * <p>With a pool the calls overlap, which is worth doing because the configured endpoint
     * was measured serving four simultaneous calls in the time it took to serve one (22
     * seconds against 91 sequential), so it does not queue them behind a single key. Without a
     * pool, for the single-batch case, the call happens inline on this thread.
     *
     * <p>Results are collected in batch order even though they arrive out of order, so the
     * output does not depend on which call happened to finish first. Progress is reported from
     * this thread as each batch in order becomes available, which keeps the counter monotonic
     * and single-threaded, and means a caller writing it to a row needs no locking. It also
     * makes the reported progress a lower bound rather than an optimistic one, since a later
     * batch that has already finished is not counted until the ones before it have.
     */
    private List<ComplianceSuggestion> runBatches(Project project,
                                                  String state,
                                                  List<ComplianceRule> candidateRules,
                                                  List<List<ComplianceRule>> batches,
                                                  ComplianceGenerationProgress progress,
                                                  ExecutorService pool) {
        List<Future<List<ComplianceSuggestion>>> futures = new ArrayList<>(batches.size());
        for (List<ComplianceRule> batchRules : batches) {
            Callable<List<ComplianceSuggestion>> call =
                    () -> responseReader.read(callModel(project, state, batchRules), batchRules);
            futures.add(pool == null ? runInline(call) : pool.submit(call));
        }

        List<ComplianceSuggestion> suggestions = new ArrayList<>(candidateRules.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                suggestions.addAll(futures.get(i).get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ComplianceAiException(
                        "Compliance generation was interrupted before it finished, so no "
                                + "compliances were generated.", e);
            } catch (ExecutionException e) {
                // Said plainly, because the honest thing to tell someone waiting is how far it
                // got and that the answer is being thrown away rather than shown half-finished.
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new ComplianceAiException(
                        "Batch " + (i + 1) + " of " + batches.size() + " failed, so the assessment "
                                + "of " + candidateRules.size() + " rule(s) is incomplete and no "
                                + "compliances were generated. " + cause.getMessage(), cause);
            }
            progress.batchCompleted(i + 1, batches.size(), suggestions.size(), candidateRules.size());
        }
        return List.copyOf(suggestions);
    }

    /**
     * Runs a batch on the calling thread and hands back its outcome as an already-completed
     * future, so the single-batch case takes the same collection path as the concurrent one
     * rather than a second path that could drift away from it.
     */
    private static Future<List<ComplianceSuggestion>> runInline(Callable<List<ComplianceSuggestion>> call) {
        CompletableFuture<List<ComplianceSuggestion>> done = new CompletableFuture<>();
        try {
            done.complete(call.call());
        } catch (Exception e) {
            done.completeExceptionally(e);
        }
        return done;
    }

    private static ThreadFactory batchThreadFactory() {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "compliance-ai-batch-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Simultaneous model calls per run, floored at one so a misconfiguration cannot stall a run. */
    private int effectiveConcurrency() {
        return Math.max(1, props.getBatchConcurrency());
    }

    /**
     * How many model calls a run of {@code ruleCount} rules will take, so a job can say what
     * it is going to do before it starts doing it.
     */
    public int batchCount(int ruleCount) {
        if (ruleCount <= 0) {
            return 0;
        }
        int size = props.getBatchSize();
        if (size <= 0 || size >= ruleCount) {
            return 1;
        }
        return (ruleCount + size - 1) / size;
    }

    /**
     * Splits the rules into consecutive groups of at most {@code batchSize}.
     *
     * <p>Consecutive rather than interleaved because the rules arrive from the repository in
     * a stable order and keeping neighbours together keeps a batch's rules related, which
     * gives the model a coherent slice of one jurisdiction to reason over instead of an
     * arbitrary scattering of it.
     */
    private static List<List<ComplianceRule>> batch(List<ComplianceRule> rules, int batchSize) {
        int size = batchSize <= 0 ? rules.size() : batchSize;
        List<List<ComplianceRule>> batches = new ArrayList<>();
        for (int from = 0; from < rules.size(); from += size) {
            batches.add(rules.subList(from, Math.min(from + size, rules.size())));
        }
        return batches;
    }

    /**
     * Issues the chat completion and returns the raw response body. Every way the call
     * itself can fail (a connect or read timeout, a proxy refusing, a 4xx or 5xx from the
     * endpoint) arrives here and leaves as a {@link ComplianceAiException}, so the caller
     * is told the run failed instead of being handed an empty result to interpret.
     *
     * <p>Package-private rather than private so a test can substitute canned responses for
     * the network. That is the only seam in this class, and it is the right one: everything
     * batching does (how the rules are split, what order the answers come back in, what
     * happens when one call of five fails) is decided around this method and is otherwise
     * only observable by making real calls with a rule catalogue large enough to break.
     */
    String callModel(Project project, String state, List<ComplianceRule> candidateRules) {
        try {
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(project, state, candidateRules);

            String requestBody = buildRequestBody(systemPrompt, userPrompt);

            RestClient client = RestClient.builder()
                    .requestFactory(requestFactory())
                    .baseUrl(props.getBaseUrl())
                    .build();

            return client.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            // The message stays out of the response body on purpose: for an HTTP error the
            // RestClient exception carries the provider's own response text, which names the
            // endpoint and is more of the upstream than an API client has any use for. The log
            // is where it belongs, and the cause chain keeps it for whoever is debugging.
            log.error("Compliance AI call failed for project {}: {}", project.getId(), e.getMessage(), e);
            throw new ComplianceAiException(
                    "The compliance AI service could not be reached or returned an error, so no "
                            + "compliances were generated. Try again in a moment.", e);
        }
    }

    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(60));
        String proxyHost = props.getProxyHost();
        if (proxyHost != null && !proxyHost.isBlank()) {
            int proxyPort = props.getProxyPort();
            factory.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)));
            log.info("Compliance AI egress routed through HTTP proxy {}:{}", proxyHost, proxyPort);
        } else {
            log.debug("Compliance AI egress is direct (no proxy configured)");
        }
        return factory;
    }

    /**
     * Builds the OpenAI chat-completions request body with the system/user message
     * split. Jackson assembles the JSON so message content is escaped correctly.
     */
    private String buildRequestBody(String systemPrompt, String userPrompt) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", props.getModel());
        root.put("max_tokens", props.getMaxTokens());
        root.put("temperature", props.getTemperature());

        ArrayNode messages = root.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", systemPrompt);
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userPrompt);

        return objectMapper.writeValueAsString(root);
    }

    private String buildSystemPrompt() {
        return "You are a construction-compliance assistant for India. Decide which statutory "
                + "compliances apply to a construction project, reasoning ONLY over the candidate "
                + "rules provided. Do not invent compliances outside this list. Assess every rule "
                + "you are given, including the ones that do not apply, and keep each rationale to "
                + "one short sentence so the whole answer fits within the response limit.";
    }

    private String buildUserPrompt(Project project, String state, List<ComplianceRule> rules) {
        StringBuilder sb = new StringBuilder();
        sb.append("Project:\n");
        sb.append("- name: ").append(project.getProjectName()).append('\n');
        sb.append("- state: ").append(state).append('\n');
        sb.append("- projectType: ").append(project.getProjectType()).append('\n');
        if (project.getProjectAddress() != null) {
            sb.append("- address: ").append(project.getProjectAddress()).append('\n');
        }
        sb.append("\nEach compliance belongs to one of three lifecycle phases: pre-construction ")
                .append("(obtained before work starts), ongoing (held or renewed during construction), ")
                .append("and post-construction (obtained on completion).\n\n");
        sb.append("Candidate rules (JSON):\n");
        sb.append("[\n");
        for (int i = 0; i < rules.size(); i++) {
            ComplianceRule r = rules.get(i);
            sb.append("  {")
                    .append("\"ruleCode\": \"").append(r.getCode()).append("\", ")
                    .append("\"name\": \"").append(escape(r.getName())).append("\", ")
                    .append("\"phase\": \"").append(r.getPhase().getValue()).append("\", ")
                    .append("\"defaultRiskLevel\": \"").append(r.getDefaultRiskLevel().getValue()).append("\", ")
                    .append("\"description\": \"").append(escape(r.getDescription())).append("\", ")
                    .append("\"authority\": \"").append(escape(r.getAuthority())).append("\"}")
                    .append(i < rules.size() - 1 ? ",\n" : "\n");
        }
        sb.append("]\n\n");
        sb.append("Respond with STRICT JSON only, no prose and no markdown fences: a JSON array where ")
                .append("each element is {\"ruleCode\": string, \"applies\": boolean, ")
                .append("\"riskLevel\": one of [low, medium, high, critical], ")
                .append("\"resolutionOptions\": array of short strings, ")
                .append("\"rationale\": short string, ")
                .append("\"phase\": one of [pre-construction, ongoing, post-construction]}. ")
                .append("Include one element for every candidate rule: the array must hold exactly ")
                .append(rules.size())
                .append(" elements, one per ruleCode listed above, and none may be omitted.");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
