package org.tornotron.echno_backend.modules.assistant.pipeline;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.concurrent.DelegatingSecurityContextCallable;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.modules.assistant.AssistantModuleEnabled;
import org.tornotron.echno_backend.modules.assistant.AssistantProperties;
import org.tornotron.echno_backend.modules.assistant.api.AssistantProvider;
import org.tornotron.echno_backend.modules.assistant.api.ProviderResult;
import org.tornotron.echno_backend.modules.assistant.api.Question;
import org.tornotron.echno_backend.modules.assistant.api.Scope;

/**
 * Step three: runs the selected providers at once, each as the caller.
 *
 * <p>A worker thread knows nothing of the request that started it, so each task is given the
 * two things a provider depends on. The caller's {@link SecurityContext} goes with it, so the
 * provider's {@code @PreAuthorize} guard judges the same user it would on the request thread. The
 * organization is pinned and a transaction opened through
 * {@link TenantScopedJobRunner#callForTenantInTransaction}, so the tenant filter is on for every
 * read the provider makes.
 *
 * <p>Uses a bounded executor queue so load never queues unbounded work while timeouts tick down;
 * a rejected task becomes {@link ProviderResult.Unavailable}. A provider that throws
 * {@link AccessDeniedException} is marked {@code accessDenied} rather than {@code Unavailable}, so
 * a caller's own permissions are not reported as a broken source.
 */
@Slf4j
@Component
@AssistantModuleEnabled
public class ParallelRetriever {

    /** What one provider returned, under its own id, and whether the caller was refused access. */
    public record ProviderOutcome(String providerId, ProviderResult result, boolean accessDenied) {

        public ProviderOutcome(String providerId, ProviderResult result) {
            this(providerId, result, false);
        }
    }

    private final TenantScopedJobRunner tenantRunner;
    private final AssistantProperties props;
    private final ThreadPoolExecutor pool;

    public ParallelRetriever(TenantScopedJobRunner tenantRunner, AssistantProperties props) {
        this.tenantRunner = tenantRunner;
        this.props = props;
        AtomicInteger sequence = new AtomicInteger();
        int threads = Math.max(1, props.getRetrieval().getMaxParallel());
        int queueCapacity = Math.max(1, props.getRetrieval().getMaxQueueSize());
        this.pool = new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "assistant-retrieve-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    /** Calls every provider using the configured per-provider timeout. */
    public List<ProviderOutcome> retrieve(List<AssistantProvider> providers, Question question, Scope scope, Long orgId) {
        long providerTimeoutMillis = TimeUnit.SECONDS.toMillis(Math.max(1, props.getRetrieval().getProviderTimeoutSeconds()));
        return retrieve(providers, question, scope, orgId, providerTimeoutMillis);
    }

    /**
     * Calls every provider and returns one outcome each, in the order given, bounded by the
     * smaller of the per-provider timeout and {@code deadlineMillis}.
     *
     * @param orgId          the caller's organization, read from the request thread by the caller
     * @param deadlineMillis remaining wall-clock budget for retrieval in milliseconds
     */
    public List<ProviderOutcome> retrieve(List<AssistantProvider> providers, Question question, Scope scope,
                                          Long orgId, long deadlineMillis) {
        long providerTimeoutMillis = TimeUnit.SECONDS.toMillis(Math.max(1, props.getRetrieval().getProviderTimeoutSeconds()));
        long budgetMillis = Math.min(providerTimeoutMillis, Math.max(0L, deadlineMillis));
        if (budgetMillis <= 0L) {
            List<ProviderOutcome> expired = new ArrayList<>(providers.size());
            for (AssistantProvider provider : providers) {
                String id = provider.describe().id();
                expired.add(new ProviderOutcome(id, new ProviderResult.Unavailable(
                        "The " + id + " source took too long to answer.")));
            }
            return expired;
        }

        SecurityContext callerContext = SecurityContextHolder.getContext();
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        List<Future<ProviderResult>> futures = new ArrayList<>(providers.size());
        List<ProviderOutcome> immediate = new ArrayList<>(providers.size());

        for (AssistantProvider provider : providers) {
            String id = provider.describe().id();
            try {
                futures.add(pool.submit(DelegatingSecurityContextCallable.create(
                        () -> tenantRunner.callForTenantInTransaction(orgId, () -> provider.retrieve(question, scope)),
                        callerContext)));
                immediate.add(null);
            } catch (RejectedExecutionException e) {
                log.warn("Assistant retrieval queue full; rejecting provider {}", id);
                futures.add(null);
                immediate.add(new ProviderOutcome(id,
                        new ProviderResult.Unavailable("The " + id + " source is busy right now.")));
            }
        }

        List<ProviderOutcome> outcomes = new ArrayList<>(providers.size());
        for (int i = 0; i < providers.size(); i++) {
            if (immediate.get(i) != null) {
                outcomes.add(immediate.get(i));
                continue;
            }
            String id = providers.get(i).describe().id();
            long remainingMillis = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
            outcomes.add(await(futures.get(i), id, remainingMillis));
        }
        return outcomes;
    }

    private static ProviderOutcome await(Future<ProviderResult> future, String id, long timeoutMillis) {
        try {
            return new ProviderOutcome(id, future.get(timeoutMillis, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Assistant provider {} did not answer within {} ms", id, timeoutMillis);
            return new ProviderOutcome(id, new ProviderResult.Unavailable("The " + id + " source took too long to answer."));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return new ProviderOutcome(id, new ProviderResult.Unavailable("The " + id + " source was interrupted."));
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AccessDeniedException) {
                return new ProviderOutcome(id, new ProviderResult.Empty("accessible " + id + " records"), true);
            }
            log.warn("Assistant provider {} failed: {}", id, e.getCause() == null ? e.getMessage() : e.getCause().toString(), e);
            return new ProviderOutcome(id, new ProviderResult.Unavailable("The " + id + " source could not be read."));
        }
    }
}
