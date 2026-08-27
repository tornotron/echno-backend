package org.tornotron.echno_backend.common.retry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the retry against a real CockroachDB rather than a stand-in: that the server's
 * serializable abort really does arrive carrying SQLSTATE 40001 once the driver, Hibernate and
 * Spring have each wrapped it, and that starting the transaction over lets the same work commit.
 *
 * <p>The abort is raised by {@code crdb_internal.force_retry}, the engine's own way of producing
 * a genuine retryable error, so the test needs no second thread and no timing window. Only the
 * first attempt asks for it; the second runs the write for real.
 *
 * <p>The context is a JPA slice with the two retry beans imported, which is the smallest thing
 * that can exercise a real transaction boundary, and it is dropped after the class so it does
 * not sit in the shared context cache for the rest of the run.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TransactionRetryTemplate.class, TransactionalWorkRunner.class, SimpleMeterRegistry.class})
@TestPropertySource(properties = {
        "echno.transaction.retry.initial-backoff-millis=0",
        "echno.transaction.retry.max-backoff-millis=0"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TransactionRetryTemplateIT extends AbstractIntegrationTest {

    private static final String OPERATION = "TransactionRetryTemplateIT.persistOrganization";

    @Autowired
    private TransactionRetryTemplate transactionRetryTemplate;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @PersistenceContext
    private EntityManager entityManager;

    // The template opens its own transaction per attempt, so the test method must not already
    // be in one; @DataJpaTest would otherwise wrap the whole method in a rolled-back transaction.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void restartsTheTransactionOnARealSerializableAbortAndCommitsTheRetry() {
        AtomicInteger attempts = new AtomicInteger();

        Long organizationId = transactionRetryTemplate.execute(OPERATION, () -> {
            if (attempts.incrementAndGet() == 1) {
                // force_retry lives behind the unsafe-internals guard from CockroachDB v26 on.
                // SET LOCAL keeps the relaxation inside this transaction, so the pooled
                // connection goes back to the pool with the guard on.
                entityManager.createNativeQuery("SET LOCAL allow_unsafe_internals = true").executeUpdate();
                entityManager.createNativeQuery("SELECT crdb_internal.force_retry('1s')").getSingleResult();
            }
            Organization organization = new Organization();
            organization.setOrganizationName("Retry Org");
            organization.setOrganizationAddress("addr");
            organization.setOrganizationEmail("retry-" + System.nanoTime() + "@example.test");
            organization.setOrganizationPhone("0000000000");
            entityManager.persist(organization);
            entityManager.flush();
            return organization.getId();
        });

        assertThat(attempts).hasValue(2);
        assertThat(organizationId).isNotNull();
        // Committed for real, not just returned: the row survives outside the template's transaction.
        assertThat(organizationRepository.findById(organizationId)).isPresent();
        assertThat(retryCount()).isEqualTo(1.0);
    }

    private double retryCount() {
        return meterRegistry.find("echno.transaction.retry.attempts").counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }
}
