package org.tornotron.echno_backend.common.documentnumber;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.retry.TransactionalWorkRunner;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the allocation against a real CockroachDB, which is the only place the claim can be
 * tested: what stops two callers taking the same number is the engine's own conflict handling
 * on the counter row, and no in-memory stand-in reproduces that.
 *
 * <p>Eight threads allocate at once for one organization and one document type. The assertion
 * is not merely that the numbers differ but that they are exactly the first eight, so a run
 * that quietly skipped or repeated one fails. Replace the single upsert with a read followed
 * by a write and this test reports duplicates.
 *
 * <p>Each caller goes through {@link TransactionRetryTemplate} the way the services do, since
 * restarting an aborted transaction is half of what makes the allocation correct: under
 * SERIALIZABLE the loser of a conflict is told to run again rather than made to wait forever.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({DocumentNumberAllocator.class, TransactionRetryTemplate.class,
        TransactionalWorkRunner.class, SimpleMeterRegistry.class})
@TestPropertySource(properties = {
        "echno.transaction.retry.initial-backoff-millis=0",
        "echno.transaction.retry.max-backoff-millis=5",
        "echno.transaction.retry.max-attempts=20"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentNumberAllocatorConcurrencyIT extends AbstractIntegrationTest {

    private static final int CALLERS = 8;

    @Autowired
    private DocumentNumberAllocator allocator;

    @Autowired
    private TransactionRetryTemplate retryTemplate;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentAllocations_eachGetTheirOwnNumber() throws Exception {
        Long orgId = new TransactionTemplate(txManager).execute(status -> {
            Organization org = new Organization();
            org.setOrganizationName("Allocator Org");
            org.setOrganizationAddress("addr");
            org.setOrganizationEmail("allocator@example.test");
            org.setOrganizationPhone("0000000000");
            entityManager.persist(org);
            entityManager.flush();
            return org.getId();
        });

        CountDownLatch startLine = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CALLERS);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int caller = 0; caller < CALLERS; caller++) {
                Callable<String> allocateOnce = () -> {
                    startLine.await();
                    return retryTemplate.execute("test.allocate",
                            () -> allocator.allocate(DocumentNumberType.PURCHASE_ORDER, orgId));
                };
                results.add(pool.submit(allocateOnce));
            }
            startLine.countDown();

            List<String> numbers = new ArrayList<>();
            for (Future<String> result : results) {
                numbers.add(result.get(60, TimeUnit.SECONDS));
            }

            assertThat(numbers).doesNotHaveDuplicates();
            assertThat(numbers).allSatisfy(number -> assertThat(number).startsWith("PO-"));
            assertThat(numbers.stream().map(n -> n.substring(n.lastIndexOf('-') + 1)).sorted().toList())
                    .containsExactly("000001", "000002", "000003", "000004",
                            "000005", "000006", "000007", "000008");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void countersAreSeparatePerOrganizationAndPerType() {
        Long first = newOrganization("counter-a@example.test");
        Long second = newOrganization("counter-b@example.test");

        String firstPo = retryTemplate.execute("test.allocate",
                () -> allocator.allocate(DocumentNumberType.PURCHASE_ORDER, first));
        String secondPo = retryTemplate.execute("test.allocate",
                () -> allocator.allocate(DocumentNumberType.PURCHASE_ORDER, second));
        String firstIndent = retryTemplate.execute("test.allocate",
                () -> allocator.allocate(DocumentNumberType.INDENT, first));

        // A second tenant starts at one of its own, which is the whole point of scoping the
        // constraint to the organization: their PO-YYYY-000001 is not a collision with ours.
        assertThat(firstPo).endsWith("-000001");
        assertThat(secondPo).endsWith("-000001");
        assertThat(firstIndent).endsWith("-000001").startsWith("IND-");
    }

    private Long newOrganization(String email) {
        return new TransactionTemplate(txManager).execute(status -> {
            Organization org = new Organization();
            org.setOrganizationName("Org " + email);
            org.setOrganizationAddress("addr");
            org.setOrganizationEmail(email);
            org.setOrganizationPhone("0000000000");
            entityManager.persist(org);
            entityManager.flush();
            return org.getId();
        });
    }
}
