package org.tornotron.echno_backend.modules.assistant.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.tornotron.echno_backend.modules.assistant.pipeline.ParallelRetriever.ProviderOutcome;

/**
 * Providers run on worker threads, and everything they depend on has to arrive there: the caller's
 * identity, so the provider's guard judges the right user; the tenant, so reads are scoped; and an
 * open transaction, so the tenant filter is on. A provider that fails or stalls costs only itself.
 */
class ParallelRetrieverTest {

    private static final ThreadLocal<Boolean> IN_TRANSACTION = ThreadLocal.withInitial(() -> false);

    private final AssistantProperties props = new AssistantProperties();
    private ParallelRetriever retriever;

    @BeforeEach
    void setUp() {
        TransactionalWorkRunner transactions = new TransactionalWorkRunner() {
            @Override
            public <T> T runInTransaction(Supplier<T> work) {
                IN_TRANSACTION.set(true);
                try {
                    return work.get();
                } finally {
                    IN_TRANSACTION.set(false);
                }
            }
        };
        props.getRetrieval().setProviderTimeoutSeconds(1);
        retriever = new ParallelRetriever(new TenantScopedJobRunner(transactions), props);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("caller-seven", null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        retriever.shutdown();
    }

    @Test
    void aWorkerThreadSeesTheCallersIdentityTheTenantAndAnOpenTransaction() {
        AtomicReference<String> user = new AtomicReference<>();
        AtomicReference<Long> org = new AtomicReference<>();
        AtomicReference<Boolean> transaction = new AtomicReference<>();
        AtomicReference<String> thread = new AtomicReference<>();
        AssistantProvider probe = provider("probe", () -> {
            user.set(SecurityContextHolder.getContext().getAuthentication().getName());
            org.set(TenantContext.getCurrentOrgId());
            transaction.set(IN_TRANSACTION.get());
            thread.set(Thread.currentThread().getName());
            return evidence("probe");
        });

        List<ProviderOutcome> outcomes = retriever.retrieve(List.of(probe), new Question("q"), scope(), 7L);

        assertThat(outcomes.get(0).result()).isInstanceOf(ProviderResult.Evidence.class);
        assertThat(thread.get()).startsWith("assistant-retrieve-").isNotEqualTo(Thread.currentThread().getName());
        assertThat(user.get()).isEqualTo("caller-seven");
        assertThat(org.get()).isEqualTo(7L);
        assertThat(transaction.get()).isTrue();
    }

    @Test
    void theCallersOwnTenantIsUntouchedWhileTheWorkersSeeTheOneTheyWereGiven() {
        TenantContext.setCurrentOrgId(99L);
        AtomicReference<Long> orgOnWorker = new AtomicReference<>();
        AssistantProvider probe = provider("probe", () -> {
            orgOnWorker.set(TenantContext.getCurrentOrgId());
            return evidence("probe");
        });

        retriever.retrieve(List.of(probe), new Question("q"), scope(), 7L);

        assertThat(orgOnWorker.get()).isEqualTo(7L);
        assertThat(TenantContext.getCurrentOrgId()).isEqualTo(99L);
    }

    @Test
    void outcomesComeBackInTheOrderTheProvidersWereGiven() {
        AssistantProvider slow = provider("slow", () -> {
            sleep(200);
            return evidence("slow");
        });
        AssistantProvider fast = provider("fast", () -> evidence("fast"));

        List<ProviderOutcome> outcomes = retriever.retrieve(List.of(slow, fast), new Question("q"), scope(), 7L);

        assertThat(outcomes).extracting(ProviderOutcome::providerId).containsExactly("slow", "fast");
    }

    @Test
    void providersRunAtTheSameTimeNotOneAfterAnother() {
        CountDownLatch bothStarted = new CountDownLatch(2);
        Supplier<ProviderResult> meetAtTheLatch = () -> {
            bothStarted.countDown();
            try {
                // Only returns in time if the other provider is running at the same moment.
                if (!bothStarted.await(900, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    return new ProviderResult.Unavailable("ran alone");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return evidence("both");
        };

        List<ProviderOutcome> outcomes = retriever.retrieve(
                List.of(provider("a", meetAtTheLatch), provider("b", meetAtTheLatch)), new Question("q"), scope(), 7L);

        assertThat(outcomes).allSatisfy(o -> assertThat(o.result()).isInstanceOf(ProviderResult.Evidence.class));
    }

    @Test
    void aProviderThatThrowsBecomesUnavailableAndTheOthersStillAnswer() {
        AssistantProvider broken = provider("broken", () -> {
            throw new IllegalStateException("jdbc:cockroach://secret-host exploded");
        });
        AssistantProvider fine = provider("fine", () -> evidence("fine"));

        List<ProviderOutcome> outcomes = retriever.retrieve(List.of(broken, fine), new Question("q"), scope(), 7L);

        assertThat(outcomes.get(0).result()).isInstanceOfSatisfying(ProviderResult.Unavailable.class, u ->
                assertThat(u.reason()).contains("broken").doesNotContain("jdbc").doesNotContain("secret-host"));
        assertThat(outcomes.get(1).result()).isInstanceOf(ProviderResult.Evidence.class);
    }

    @Test
    void aProviderThatRefusesTheCallerIsMarkedAccessDeniedAndNotASourceFailure() {
        AssistantProvider refusing = provider("attendance", () -> {
            throw new AccessDeniedException("Access Denied");
        });

        ProviderOutcome outcome = retriever.retrieve(List.of(refusing), new Question("q"), scope(), 7L).get(0);

        assertThat(outcome.accessDenied()).isTrue();
        assertThat(outcome.result()).isNotInstanceOf(ProviderResult.Unavailable.class);
    }

    @Test
    void whenTheBoundedQueueIsFullExtraTasksAreRejectedAsUnavailable() {
        AssistantProperties tightProps = new AssistantProperties();
        tightProps.getRetrieval().setMaxParallel(1);
        tightProps.getRetrieval().setMaxQueueSize(1);
        tightProps.getRetrieval().setProviderTimeoutSeconds(1);

        TransactionalWorkRunner transactions = new TransactionalWorkRunner() {
            @Override
            public <T> T runInTransaction(Supplier<T> work) {
                return work.get();
            }
        };
        ParallelRetriever tightRetriever = new ParallelRetriever(new TenantScopedJobRunner(transactions), tightProps);
        try {
            CountDownLatch release = new CountDownLatch(1);
            AssistantProvider first = provider("first", () -> {
                try {
                    release.await(1, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return evidence("first");
            });
            AssistantProvider second = provider("second", () -> evidence("second"));
            AssistantProvider third = provider("third", () -> evidence("third"));

            // With 1 thread + queue size 1: first runs, second queues, third is rejected immediately.
            List<ProviderOutcome> outcomes = tightRetriever.retrieve(
                    List.of(first, second, third), new Question("q"), scope(), 7L);
            release.countDown();

            assertThat(outcomes.get(2).result()).isInstanceOfSatisfying(ProviderResult.Unavailable.class, u ->
                    assertThat(u.reason()).contains("busy"));
        } finally {
            tightRetriever.shutdown();
        }
    }

    @Test
    void anExpiredDeadlineReturnsUnavailableWithoutRunningTheProvider() {
        AtomicReference<Boolean> ran = new AtomicReference<>(false);
        AssistantProvider probe = provider("probe", () -> {
            ran.set(true);
            return evidence("probe");
        });

        ProviderOutcome outcome = retriever.retrieve(List.of(probe), new Question("q"), scope(), 7L, 0L).get(0);

        assertThat(outcome.result()).isInstanceOf(ProviderResult.Unavailable.class);
        assertThat(ran.get()).isFalse();
    }

    @Test
    void aProviderThatRunsPastItsTimeIsUnavailableAndDoesNotHoldUpTheAnswer() {
        AssistantProvider stalled = provider("stalled", () -> {
            sleep(5_000);
            return evidence("stalled");
        });
        long started = System.nanoTime();

        ProviderResult result = retriever.retrieve(List.of(stalled), new Question("q"), scope(), 7L).get(0).result();
        long tookMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(result).isInstanceOfSatisfying(ProviderResult.Unavailable.class, u ->
                assertThat(u.reason()).contains("too long"));
        assertThat(tookMs).isLessThan(3_000);
    }

    @Test
    void aMissingOrganizationIsRefusedNotRunUnscoped() {
        AssistantProvider probe = provider("probe", () -> evidence("probe"));

        ProviderResult result = retriever.retrieve(List.of(probe), new Question("q"), scope(), null).get(0).result();

        // The runner refuses a null tenant, which the retriever reports as the source being unreadable.
        assertThat(result).isInstanceOf(ProviderResult.Unavailable.class);
    }

    private static Scope scope() {
        return new Scope(42L, null, null, null);
    }

    private static ProviderResult evidence(String id) {
        return new ProviderResult.Evidence(List.of(
                new EvidenceUnit(id + ":1", id, "kind", Map.of("value", 1), SourceRef.entity("test", id), 1.0)));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static AssistantProvider provider(String id, Supplier<ProviderResult> body) {
        ProviderDescriptor descriptor = new ProviderDescriptor(id, "answers things",
                List.of(new FieldSpec("value", "units", "a value")), Set.of(Subject.TASK), false, false, new CostProfile(1, 0));
        return new AssistantProvider() {
            @Override
            public ProviderDescriptor describe() {
                return descriptor;
            }

            @Override
            public ProviderResult retrieve(Question question, Scope scope) {
                return body.get();
            }
        };
    }
}
