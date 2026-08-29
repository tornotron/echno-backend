package org.tornotron.echno_backend.stockAdjustment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link StockAdjustmentRepository} against a real CockroachDB
 * (see {@link AbstractIntegrationTest}). Asserts the tenant-scoped lookup returns the
 * header together with its line item, that a persisted document shows up in a
 * paginated {@code findAll}, and that the locking lookup the decision paths take
 * actually serializes them.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class StockAdjustmentRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private StockAdjustmentRepository stockAdjustmentRepository;

    @Autowired
    private TestEntityManager em;

    @Autowired
    private PlatformTransactionManager txManager;

    @Test
    void findByIdAndOrganization_returnsTheDocumentWithItsLineItem() {
        Organization org = persistOrganization("Org A");
        StockAdjustment adjustment = persistStockAdjustment(org, "SA-0001");
        em.flush();
        em.clear();

        Optional<StockAdjustment> found =
                stockAdjustmentRepository.findByIdAndOrganization_Id(adjustment.getId(), org.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getAdjustmentNumber()).isEqualTo("SA-0001");
        assertThat(found.get().getOrganization().getId()).isEqualTo(org.getId());
        assertThat(found.get().getLineItems()).hasSize(1);
        assertThat(found.get().getLineItems().get(0).getDescription()).isEqualTo("Cement bags");
    }

    @Test
    void findAll_paginated_includesThePersistedDocument() {
        Organization org = persistOrganization("Org B");
        persistStockAdjustment(org, "SA-0002");
        em.flush();
        em.clear();

        Page<StockAdjustment> result = stockAdjustmentRepository.findAll(PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(StockAdjustment::getAdjustmentNumber)
                .contains("SA-0002");
    }

    /**
     * A stock adjustment is decided by reading its state, checking it has not been decided
     * already, and then writing. Approve, reject, update and delete all do that, so two of them
     * running at once on the same document would each read the state as it stood before the
     * other and both act: the ledger would carry the movement twice, or carry it under a
     * document recorded as refused. This mirrors that race with the guard the service applies,
     * through the same locking lookup. Without the lock every thread reads the undecided
     * document and every thread decides it; with it they serialize and exactly one wins.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentDecisions_onlyOneWins() throws Exception {
        long[] ids = new TransactionTemplate(txManager).execute(status -> {
            Organization org = new Organization();
            org.setOrganizationName("Decision Org");
            org.setOrganizationAddress("Decision address");
            org.setOrganizationEmail("decision@example.test");
            org.setOrganizationPhone("0000000000");
            em.getEntityManager().persist(org);

            StockAdjustment adjustment = new StockAdjustment();
            adjustment.setOrganization(org);
            adjustment.setAdjustmentNumber("SA-0003");
            adjustment.setType("physical_count");
            adjustment.setStatus("draft");
            adjustment.setJustification("Contended count");
            em.getEntityManager().persist(adjustment);

            em.getEntityManager().flush();
            return new long[] { adjustment.getId(), org.getId() };
        });
        long adjustmentId = ids[0];
        long orgId = ids[1];

        int threads = 4;
        AtomicInteger decisions = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();
                new TransactionTemplate(txManager).executeWithoutResult(status -> {
                    StockAdjustment adjustment = stockAdjustmentRepository
                            .lockByIdAndOrganizationId(adjustmentId, orgId)
                            .orElseThrow();
                    // Mirror the service guards: decide only an undecided document.
                    if (adjustment.getProcessedAt() == null && adjustment.getRejectedAt() == null) {
                        adjustment.setStatus("rejected");
                        adjustment.setRejectedAt(LocalDateTime.now());
                        adjustment.setRejectionReason("Variance not supported by the count sheet");
                        stockAdjustmentRepository.save(adjustment);
                        decisions.incrementAndGet();
                    }
                });
                return null;
            }));
        }
        startGate.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(decisions.get()).isEqualTo(1);
    }

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        org.setOrganizationPhone("0000000000");
        em.persist(org);
        return org;
    }

    private StockAdjustment persistStockAdjustment(Organization org, String number) {
        StockAdjustment adjustment = new StockAdjustment();
        adjustment.setOrganization(org);
        adjustment.setAdjustmentNumber(number);
        adjustment.setType("physical_count");
        adjustment.setStatus("draft");
        adjustment.setJustification("Year-end stock count");

        StockAdjustmentLineItem item = new StockAdjustmentLineItem();
        item.setDescription("Cement bags");
        item.setSystemQuantity(100.0);
        item.setPhysicalQuantity(95.0);
        item.setAdjustmentQuantity(-5.0);
        item.setUnit("bags");
        item.setReason("write_off");
        item.setOrganization(org);
        adjustment.addLineItem(item);

        em.persist(adjustment);
        return adjustment;
    }
}
