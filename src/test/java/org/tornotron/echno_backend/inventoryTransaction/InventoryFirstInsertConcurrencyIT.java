package org.tornotron.echno_backend.inventoryTransaction;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.material.Material;
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
 * Concurrency test for the FIRST insert of a no-location stock row against a real
 * CockroachDB. Two threads increment a material/project that has no stock row yet, so
 * both race to create it. Without the seed-then-lock path (and the partial unique index
 * behind it) both would insert a separate no-location row: the total would still sum
 * correctly but the stock would be split across duplicates that later locks each miss.
 * With the fix exactly one row exists and the quantity is exact.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(InventoryService.class)
class InventoryFirstInsertConcurrencyIT extends AbstractIntegrationTest {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private CurrentStockRepository currentStockRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentFirstInserts_createOneRow_andDoNotLoseUpdates() throws Exception {
        // Seed only the organization, material and project - deliberately NO stock row,
        // so the worker threads exercise the first-insert path.
        long[] ids = new TransactionTemplate(txManager).execute(status -> {
            Organization org = new Organization();
            org.setOrganizationName("First Insert Org");
            org.setOrganizationAddress("addr");
            org.setOrganizationEmail("first-insert@example.test");
            org.setOrganizationPhone("0000000000");
            entityManager.persist(org);

            Material material = new Material();
            material.setMaterialName("Cement");
            material.setUnit("bag");
            material.setOrganization(org);
            entityManager.persist(material);

            Project project = new Project();
            project.setProjectName("Project 1");
            project.setOrganization(org);
            entityManager.persist(project);

            entityManager.flush();
            return new long[] {org.getId(), material.getId(), project.getId()};
        });

        Organization org = new Organization();
        org.setId(ids[0]);
        Material material = new Material();
        material.setId(ids[1]);
        Project project = new Project();
        project.setId(ids[2]);

        int threads = 2;
        double increment = 10.0;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();
                inventoryService.updateCurrentStock(material, project, null, org, increment, BigDecimal.ONE);
                return null;
            }));
        }
        startGate.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        Double finalQuantity = currentStockRepository.sumCurrentQuantityByMaterialAndProject(ids[1], ids[2]);
        assertThat(finalQuantity).isEqualTo(threads * increment);

        long rowCount = currentStockRepository.findAll().stream()
                .filter(cs -> cs.getMaterial().getId().equals(ids[1])
                        && cs.getProject().getId().equals(ids[2])
                        && cs.getStorageLocation() == null)
                .count();
        assertThat(rowCount).isEqualTo(1);
    }
}
