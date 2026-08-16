package org.tornotron.echno_backend.payable;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency test for the payable payment lock against a real CockroachDB.
 * Several threads record a payment against the same payable at once; without the
 * pessimistic write lock that {@code recordPayment} now takes on the lookup, they
 * read the same {@code amountPaid} and write over each other, losing payments.
 * With the lock they serialize, so the recorded total is exact.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PayableConcurrencyIT extends AbstractIntegrationTest {

    @Autowired
    private PayableRepository payableRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentPayments_doNotLoseUpdates() throws Exception {
        long[] ids = new TransactionTemplate(txManager).execute(status -> {
            Organization org = new Organization();
            org.setOrganizationName("Payable Org");
            org.setOrganizationAddress("addr");
            org.setOrganizationEmail("payable@example.test");
            org.setOrganizationPhone("0000000000");
            entityManager.persist(org);

            Project project = new Project();
            project.setProjectName("Project 1");
            project.setOrganization(org);
            entityManager.persist(project);

            Payable payable = new Payable();
            payable.setPayableNumber("PAY-0001");
            payable.setContractorName("ACME Contractors");
            payable.setProject(project);
            payable.setOrganization(org);
            payable.setAmountRecorded(new BigDecimal("1000.00"));
            payable.setAmountPaid(BigDecimal.ZERO);
            entityManager.persist(payable);

            entityManager.flush();
            return new long[] {org.getId(), payable.getId()};
        });

        long orgId = ids[0];
        long payableId = ids[1];
        int threads = 4;
        BigDecimal increment = new BigDecimal("10.00");

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();
                new TransactionTemplate(txManager).executeWithoutResult(status -> {
                    Payable payable = payableRepository
                            .lockByIdAndOrganizationId(payableId, orgId)
                            .orElseThrow();
                    BigDecimal current = payable.getAmountPaid() == null ? BigDecimal.ZERO : payable.getAmountPaid();
                    payable.setAmountPaid(current.add(increment));
                    payableRepository.save(payable);
                });
                return null;
            }));
        }
        startGate.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        Payable finalPayable = payableRepository.findByIdAndOrganization_Id(payableId, orgId).orElseThrow();
        assertThat(finalPayable.getAmountPaid()).isEqualByComparingTo(increment.multiply(BigDecimal.valueOf(threads)));
    }
}
