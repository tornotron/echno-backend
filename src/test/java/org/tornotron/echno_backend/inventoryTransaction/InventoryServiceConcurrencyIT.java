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
 * Concurrency test for the stock mutation path against a real CockroachDB. Several
 * threads increment the same stock row at once; without the pessimistic write lock
 * added to the lookup they would read-modify-write over each other and lose
 * updates. With the lock they serialize, so the final quantity is exact.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(InventoryService.class)
class InventoryServiceConcurrencyIT extends AbstractIntegrationTest {

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
    void concurrentStockIncrements_doNotLoseUpdates() throws Exception {
        // Seed an organization, material, project, and a zero stock row in a
        // committed transaction so the worker threads can read it.
        long[] ids = new TransactionTemplate(txManager).execute(status -> {
            Organization org = new Organization();
            org.setOrganizationName("Inventory Org");
            org.setOrganizationAddress("addr");
            org.setOrganizationEmail("inventory@example.test");
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

            CurrentStock stock = new CurrentStock();
            stock.setMaterial(material);
            stock.setProject(project);
            stock.setOrganization(org);
            stock.setCurrentQuantity(0.0);
            stock.setStockValue(BigDecimal.ZERO);
            entityManager.persist(stock);

            entityManager.flush();
            return new long[] {org.getId(), material.getId(), project.getId()};
        });

        // Lightweight detached references (only the id is read on the update path).
        Organization org = new Organization();
        org.setId(ids[0]);
        Material material = new Material();
        material.setId(ids[1]);
        Project project = new Project();
        project.setId(ids[2]);

        int threads = 4;
        double increment = 10.0;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();
                // Each call runs in its own transaction (updateCurrentStock is
                // @Transactional), taking the pessimistic lock on the stock row.
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
    }
}
