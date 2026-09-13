package org.tornotron.echno_backend.modules.inspections.dataset;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.tornotron.echno_backend.common.exception.TenantAccessDeniedException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantIsolationListenerRegistrar;
import org.tornotron.echno_backend.common.multitenancy.UnscopedAccessGuard;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The export's two tables against the real schema (#791): a run and its ledger rows belong
 * to one organization and are invisible from another, and the ledger refuses a second row
 * for the same source object, which is what makes the export idempotent under a race.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TenantIsolationListenerRegistrar.class, UnscopedAccessGuard.class, SimpleMeterRegistry.class})
class DatasetExportTenantIsolationIT extends AbstractIntegrationTest {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private DatasetExportRunRepository runRepository;

    @Autowired
    private DatasetExportedItemRepository itemRepository;

    private Long orgAId;
    private Long orgBId;
    private UUID runId;
    private Organization orgA;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        orgA = persistOrganization("Export Org A");
        Organization orgB = persistOrganization("Export Org B");

        DatasetExportRun run = new DatasetExportRun();
        run.setOrganization(orgA);
        run.setRunKey("run-test-" + UUID.randomUUID().toString().substring(0, 8));
        run.setTriggeredBy("test");
        run.setStartedAt(LocalDateTime.of(2026, 9, 13, 1, 0));
        entityManager.persist(run);

        entityManager.persist(item(orgA, run.getId(), "11"));

        entityManager.flush();
        orgAId = orgA.getId();
        orgBId = orgB.getId();
        runId = run.getId();
        entityManager.clear();
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void theOwningOrganizationSeesItsRunAndLedger() {
        TenantContext.setCurrentOrgId(orgAId);

        assertThat(runRepository.findByIdAndOrganization_Id(runId, orgAId)).isPresent();
        assertThat(runRepository.findByOrganization_IdOrderByStartedAtDesc(orgAId)).hasSize(1);
        assertThat(itemRepository.findSourceRefs(orgAId, DatasetSourceKind.INSPECTION_EVIDENCE)).containsExactly("11");
        assertThat(itemRepository.findByRunIdOrderByCreatedAtAsc(runId)).hasSize(1);
    }

    @Test
    void anotherOrganizationSeesNothingAndIsRefusedTheRowItself() {
        TenantContext.setCurrentOrgId(orgBId);

        assertThat(runRepository.findByIdAndOrganization_Id(runId, orgBId)).isEmpty();
        assertThat(runRepository.findByOrganization_IdOrderByStartedAtDesc(orgBId)).isEmpty();
        assertThat(itemRepository.findSourceRefs(orgBId, DatasetSourceKind.INSPECTION_EVIDENCE)).isEmpty();
        // the rows themselves cannot be loaded from the other tenant, by id or by run
        assertThatThrownBy(() -> runRepository.findById(runId))
                .isInstanceOf(TenantAccessDeniedException.class);
        assertThatThrownBy(() -> itemRepository.findByRunIdOrderByCreatedAtAsc(runId))
                .isInstanceOf(TenantAccessDeniedException.class);
    }

    @Test
    void theLedgerRefusesASecondRowForTheSameSourceObject() {
        TenantContext.setCurrentOrgId(orgAId);
        Organization owner = entityManager.getReference(Organization.class, orgAId);

        itemRepository.save(item(owner, runId, "12"));
        assertThatThrownBy(() -> {
            itemRepository.save(item(owner, UUID.randomUUID(), "11"));
            entityManager.flush();
        }).isInstanceOfAny(DataIntegrityViolationException.class, ConstraintViolationException.class);
    }

    private static DatasetExportedItem item(Organization owner, UUID runId, String sourceRef) {
        DatasetExportedItem item = new DatasetExportedItem();
        item.setOrganization(owner);
        item.setRunId(runId);
        item.setSourceKind(DatasetSourceKind.INSPECTION_EVIDENCE);
        item.setSourceRef(sourceRef);
        item.setSourceKey("inspection/" + sourceRef + ".jpg");
        item.setExportKey("construction-images/export/run/inspection-evidence/att-" + sourceRef + ".jpg");
        return item;
    }

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        org.setOrganizationPhone("0000000000");
        entityManager.persist(org);
        return org;
    }
}
