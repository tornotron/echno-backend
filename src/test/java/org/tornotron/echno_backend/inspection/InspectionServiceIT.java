package org.tornotron.echno_backend.inspection;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.inspection.dtos.CreateInspectionRequest;
import org.tornotron.echno_backend.inspection.dtos.InspectionCheckItemRequest;
import org.tornotron.echno_backend.inspection.dtos.InspectionDefectRequest;
import org.tornotron.echno_backend.inspection.dtos.InspectionDto;
import org.tornotron.echno_backend.inspection.dtos.UpdateInspectionRequest;
import org.tornotron.echno_backend.inspection.mapper.InspectionMapperImpl;
import org.tornotron.echno_backend.inspection.repositories.InspectionRepository;
import org.tornotron.echno_backend.inspection.service.InspectionService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end exercise of the inspection CRUD path against a real CockroachDB:
 * create derives the summary counts from the check items and defects, get and
 * list return them, update rebuilds the children and recomputes the counts, and
 * the org filter keeps one tenant's inspections invisible to another.
 *
 * <p>No {@code JpaAuditingConfig} import is needed: the created and updated
 * timestamps are populated by Hibernate's {@code @CreationTimestamp}/{@code
 * @UpdateTimestamp} at persist time, not by Spring Data auditing.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({InspectionService.class, InspectionMapperImpl.class,
        TenantEntityHelper.class, EntryNumberGenerator.class})
class InspectionServiceIT extends AbstractIntegrationTest {

    @Autowired
    private InspectionService service;

    @Autowired
    private InspectionRepository inspectionRepo;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private Long orgAId;
    private Long orgBId;
    private Long projectId;

    // The tenant seed (orgs + project) must be committed, not held in the test's
    // rolled-back transaction: EntryNumberGenerator.next() runs in REQUIRES_NEW, so
    // the document_sequence insert commits in a separate transaction that only sees
    // committed rows. The inspection itself stays in the rolled-back test transaction.
    @BeforeEach
    void seed() {
        TenantContext.clear();
        inCommittedTx(() -> {
            Organization orgA = persistOrganization("Org A");
            Organization orgB = persistOrganization("Org B");

            Project project = new Project();
            project.setProjectName("Tower A");
            project.setOrganization(orgA);
            entityManager.persist(project);

            entityManager.flush();
            orgAId = orgA.getId();
            orgBId = orgB.getId();
            projectId = project.getId();
        });

        TenantContext.setCurrentOrgId(orgAId);
    }

    @AfterEach
    void cleanup() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
        TenantContext.clear();
        if (orgAId == null && orgBId == null) {
            return;
        }
        // Committed seed rows survive the test rollback, so remove them by hand in
        // FK-safe order (also clears the committed document_sequence rows).
        inCommittedTx(() -> {
            deleteForOrgs("DELETE FROM inspection_defect_photos WHERE defect_id IN "
                    + "(SELECT d.id FROM inspection_defects d JOIN inspections i ON d.inspection_id = i.id "
                    + "WHERE i.organization_id IN (:a,:b))");
            deleteForOrgs("DELETE FROM inspection_check_item_photos WHERE check_item_id IN "
                    + "(SELECT c.id FROM inspection_check_items c JOIN inspections i ON c.inspection_id = i.id "
                    + "WHERE i.organization_id IN (:a,:b))");
            deleteForOrgs("DELETE FROM inspection_attendees WHERE inspection_id IN "
                    + "(SELECT id FROM inspections WHERE organization_id IN (:a,:b))");
            deleteForOrgs("DELETE FROM inspection_defects WHERE inspection_id IN "
                    + "(SELECT id FROM inspections WHERE organization_id IN (:a,:b))");
            deleteForOrgs("DELETE FROM inspection_check_items WHERE inspection_id IN "
                    + "(SELECT id FROM inspections WHERE organization_id IN (:a,:b))");
            deleteForOrgs("DELETE FROM inspections WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM document_sequence WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM project WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM organization WHERE id IN (:a,:b)");
        });
    }

    private void deleteForOrgs(String sql) {
        entityManager.createNativeQuery(sql)
                .setParameter("a", orgAId)
                .setParameter("b", orgBId)
                .executeUpdate();
    }

    private void inCommittedTx(Runnable work) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.executeWithoutResult(status -> work.run());
    }

    @Test
    void create_get_list_update_derivesCountsAndScopesByTenant() {
        CreateInspectionRequest createReq = new CreateInspectionRequest(
                "Third floor slab check",
                InspectionType.QUALITY,
                projectId,
                "Block A, Level 3",
                "Slab and columns",
                "STR-03-REV2",
                LocalDate.of(2026, 8, 20),
                "09:30",
                null, null, 90,
                100L,
                200L,
                "Client Rep",
                List.of("Site Engineer", "Safety Officer"),
                "Clear",
                "32C",
                List.of(
                        new InspectionCheckItemRequest("Structural", "Column alignment",
                                "Within 5mm", CheckItemStatus.PASSED, null, false, null,
                                "3mm", "5mm", "high"),
                        new InspectionCheckItemRequest("Structural", "Rebar spacing",
                                "150mm c/c", CheckItemStatus.PASSED, null, false, null,
                                null, null, "medium"),
                        new InspectionCheckItemRequest("Finishing", "Surface level",
                                "Level within tolerance", CheckItemStatus.FAILED, "Uneven patch",
                                true, List.of("photo-1.jpg"), null, null, "low")
                ),
                List.of(
                        new InspectionDefectRequest("Finishing", "Uneven surface near grid B2",
                                "minor", "Grid B2", List.of("defect-1.jpg"),
                                "Re-level and re-finish", "Contractor",
                                LocalDate.of(2026, 8, 25), "open", null)
                )
        );

        // create - counts derived from the children, status forced to SCHEDULED
        InspectionDto created = service.create(createReq);
        assertThat(created.status()).isEqualTo(InspectionStatus.SCHEDULED);
        assertThat(created.result()).isNull();
        assertThat(created.inspectionNumber()).startsWith("INSP-");
        assertThat(created.checkItems()).hasSize(3);
        assertThat(created.defects()).hasSize(1);
        assertThat(created.attendees()).containsExactly("Site Engineer", "Safety Officer");
        assertThat(created.totalCheckPoints()).isEqualTo(3);
        assertThat(created.passedCheckPoints()).isEqualTo(2);
        assertThat(created.failedCheckPoints()).isEqualTo(1);
        assertThat(created.defectsFound()).isEqualTo(1);
        assertThat(created.createdAt()).isNotNull();

        UUID id = created.id();

        // get
        InspectionDto fetched = service.findById(id);
        assertThat(fetched.id()).isEqualTo(id);
        assertThat(fetched.checkItems()).hasSize(3);
        assertThat(fetched.defects()).hasSize(1);

        entityManager.flush();
        entityManager.clear();

        Pageable pageable = PageRequest.of(0, 10);

        // tenant scoping - invisible to another organization
        enableOrgFilter(orgBId);
        assertThat(service.findAll(null, null, null, null, pageable).getTotalElements()).isZero();
        assertThat(inspectionRepo.findByIdScoped(id)).isEmpty();

        // visible and listable to the owning organization
        disableOrgFilter();
        enableOrgFilter(orgAId);
        assertThat(service.findAll(null, null, null, null, pageable).getTotalElements()).isEqualTo(1);
        assertThat(service.findAll(projectId, InspectionStatus.SCHEDULED,
                InspectionType.QUALITY, null, pageable).getTotalElements()).isEqualTo(1);
        assertThat(inspectionRepo.findByIdScoped(id)).isPresent();
        disableOrgFilter();

        // update - status and result set directly, children rebuilt, counts recomputed
        UpdateInspectionRequest updateReq = new UpdateInspectionRequest(
                "Third floor slab check",
                InspectionType.QUALITY,
                InspectionStatus.COMPLETED,
                InspectionResult.PASSED,
                projectId,
                "Block A, Level 3",
                "Slab and columns",
                "STR-03-REV2",
                LocalDate.of(2026, 8, 20),
                "09:30",
                null, null, 60,
                100L,
                200L,
                "Client Rep",
                List.of("Site Engineer"),
                "Clear",
                "30C",
                List.of(
                        new InspectionCheckItemRequest("Structural", "Column alignment",
                                "Within 5mm", CheckItemStatus.PASSED, null, false, null,
                                null, null, "high")
                ),
                List.of()
        );

        InspectionDto updated = service.update(id, updateReq);
        assertThat(updated.status()).isEqualTo(InspectionStatus.COMPLETED);
        assertThat(updated.result()).isEqualTo(InspectionResult.PASSED);
        assertThat(updated.checkItems()).hasSize(1);
        assertThat(updated.defects()).isEmpty();
        assertThat(updated.attendees()).containsExactly("Site Engineer");
        assertThat(updated.totalCheckPoints()).isEqualTo(1);
        assertThat(updated.passedCheckPoints()).isEqualTo(1);
        assertThat(updated.failedCheckPoints()).isZero();
        assertThat(updated.defectsFound()).isZero();
    }

    private void enableOrgFilter(Long orgId) {
        entityManager.unwrap(Session.class)
                .enableFilter("orgFilter")
                .setParameter("organizationId", orgId);
    }

    private void disableOrgFilter() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
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
