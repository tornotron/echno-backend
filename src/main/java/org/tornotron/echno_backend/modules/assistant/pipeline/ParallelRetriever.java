package org.tornotron.echno_backend.modules.assistant.pipeline;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * read the provider makes. The plain {@code callForTenant} would pin the tenant and open no
 * transaction, and a provider reading through it would see every organization's rows (#877).
 *
 * <p>Whatever a provider does, it comes back as a {@link ProviderResult}: a provider that
 * throws, is refused, or runs past its time becomes {@code Unavailable} with a reason that names
 * nothing internal, so one slow or broken source costs the answer that source and no more.
 */
@Slf4j
@Component
@AssistantModuleEnabled
public class ParallelRetriever {

    /** What one provider returned, under its own id. */
    public record ProviderOutcome(String providerId, ProviderResult result) {
    }

    private final TenantScopedJobRunner tenantRunner;
    private final AssistantProperties props;
    private final ExecutorService pool;

    public ParallelRetriever(TenantScopedJobRunner tenantRunner, AssistantProperties props) {
        this.tenantRunner = tenantRunner;
        this.props = props;
        AtomicInteger sequence = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(Math.max(1, props.getRetrieval().getMaxParallel()), runnable -> {
            Thread thread = new Thread(runnable, "assistant-retrieve-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    /**
     * Calls every provider and returns one outcome each, in the order given.
     *
     * @param orgId the caller's organization, read from the request thread by the caller
     */
    public List<ProviderOutcome> retrieve(List<AssistantProvider> providers, Question question, Scope scope, Long orgId) {
        SecurityContext callerContext = SecurityContextHolder.getContext();
        List<Future<ProviderResult>> futures = new ArrayList<>(providers.size());
        for (AssistantProvider provider : providers) {
            futures.add(pool.submit(DelegatingSecurityContextCallable.create(
                    () -> tenantRunner.callForTenantInTransaction(orgId, () -> provider.retrieve(question, scope)),
                    callerContext)));
        }

        List<ProviderOutcome> outcomes = new ArrayList<>(providers.size());
        long timeoutSeconds = Math.max(1, props.getRetrieval().getProviderTimeoutSeconds());
        for (int i = 0; i < providers.size(); i++) {
            String id = providers.get(i).describe().id();
            outcomes.add(new ProviderOutcome(id, await(futures.get(i), id, timeoutSeconds)));
        }
        return outcomes;
    }

    private static ProviderResult await(Future<ProviderResult> future, String id, long timeoutSeconds) {
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Assistant provider {} did not answer within {} seconds", id, timeoutSeconds);
            return new ProviderResult.Unavailable("The " + id + " source took too long to answer.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return new ProviderResult.Unavailable("The " + id + " source was interrupted.");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AccessDeniedException) {
                return new ProviderResult.Unavailable("You do not have access to the " + id + " source.");
            }
            log.warn("Assistant provider {} failed: {}", id, e.getCause() == null ? e.getMessage() : e.getCause().toString(), e);
            return new ProviderResult.Unavailable("The " + id + " source could not be read.");
        }
    }
}
