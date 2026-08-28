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
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.inspection.dtos.ChecklistTemplateItemRequest;
import org.tornotron.echno_backend.inspection.dtos.ChecklistTemplateRequest;
import org.tornotron.echno_backend.inspection.dtos.CreateInspectionRequest;
import org.tornotron.echno_backend.inspection.dtos.InspectionCheckItemDto;
import org.tornotron.echno_backend.inspection.dtos.InspectionCheckItemRequest;
import org.tornotron.echno_backend.inspection.dtos.InspectionDefectRequest;
import org.tornotron.echno_backend.inspection.dtos.InspectionDto;
import org.tornotron.echno_backend.inspection.dtos.UpdateInspectionRequest;
import org.tornotron.echno_backend.inspection.mapper.ChecklistTemplateMapperImpl;
import org.tornotron.echno_backend.inspection.mapper.InspectionMapperImpl;
import org.tornotron.echno_backend.inspection.mapper.DefectPhotoAnnotationMapperImpl;
import org.tornotron.echno_backend.inspection.mapper.NcrMapperImpl;
import org.tornotron.echno_backend.inspection.repositories.InspectionRepository;
import org.tornotron.echno_backend.inspection.service.ChecklistTemplateService;
import org.tornotron.echno_backend.inspection.service.DefectAnnotationService;
import org.tornotron.echno_backend.inspection.service.InspectionService;
import org.tornotron.echno_backend.inspection.service.NcrService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end exercise of the inspection CRUD path against a real CockroachDB:
 * create derives the summary counts from the check items and defects, get and
 * list return them, update rebuilds the children and recomputes the counts, the
 * category is derived from the type when the request omits it, and the org filter
 * keeps one tenant's inspections invisible to another.
 *
 * <p>No {@code JpaAuditingConfig} import is needed: the created and updated
 * timestamps are populated by Hibernate's {@code @CreationTimestamp}/{@code
 * @UpdateTimestamp} at persist time, not by Spring Data auditing.
 *
 * <p>{@link ChecklistTemplateServiceIT}, {@link NcrServiceIT} and
 * {@link InspectionTaxonomyMigrationIT} declare the same annotations and the same
 * {@code @Import} list, deliberately and to the letter, so Spring's context cache
 * hands all four classes one context instead of building four. The test JVM is
 * capped at 1 GB with no fork between classes, so every distinct test configuration
 * is a Spring context that stays cached for the whole run. Keep the lists identical
 * when any one changes.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({InspectionService.class, InspectionMapperImpl.class,
        ChecklistTemplateService.class, ChecklistTemplateMapperImpl.class,
        NcrService.class, NcrMapperImpl.class,
        DefectAnnotationService.class, DefectPhotoAnnotationMapperImpl.class,
        UserContextService.class,
        TenantEntityHelper.class, EntryNumberGenerator.class})
class InspectionServiceIT extends AbstractIntegrationTest {

    @Autowired
    private InspectionService service;

    @Autowired
    private InspectionRepository inspectionRepo;

    @Autowired
    private ChecklistTemplateService templateService;

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

    /**
     * Resets the per-test session state while the test transaction is still open.
     * The database rows are removed separately by {@link #removeCommittedRows()},
     * once that transaction has gone.
     */
    @AfterEach
    void clearTenantState() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
        TenantContext.clear();
    }

    /**
     * Removes the rows the seed committed, after the test transaction has rolled
     * back rather than while it is still open.
     *
     * <p>{@code @AfterEach} runs inside the test transaction, so a delete issued
     * from there runs in a second, committed transaction while the first still
     * holds write intents on the same tables. CockroachDB may resolve that by
     * aborting the writer, or it may make the deleter wait for a transaction that
     * cannot commit until the delete returns. When it waits, the statement timeout
     * fires 30 seconds later and the test fails on cleanup with a query timeout and
     * no failed assertion, which is exactly the kind of failure that gets blamed on
     * an unrelated test. {@code @AfterTransaction} runs after the rollback, so
     * there are no intents left to contend with.
     */
    @AfterTransaction
    void removeCommittedRows() {
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
            deleteForOrgs("DELETE FROM checklist_template_items WHERE template_id IN "
                    + "(SELECT id FROM checklist_templates WHERE organization_id IN (:a,:b))");
            deleteForOrgs("DELETE FROM checklist_templates WHERE organization_id IN (:a,:b)");
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
                null,
                InspectionTrade.RCC,
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
                                "3mm", "5mm", null, null, null, "high"),
                        new InspectionCheckItemRequest("Structural", "Rebar spacing",
                                "150mm c/c", CheckItemStatus.PASSED, null, false, null,
                                null, null, null, null, null, "medium"),
                        new InspectionCheckItemRequest("Finishing", "Surface level",
                                "Level within tolerance", CheckItemStatus.FAILED, "Uneven patch",
                                true, List.of("photo-1.jpg"), null, null, null, null, null, "low")
                ),
                List.of(
                        new InspectionDefectRequest("Finishing", "Uneven surface near grid B2",
                                DefectSeverity.MINOR, "Grid B2", List.of("defect-1.jpg"),
                                "Re-level and re-finish", "Contractor",
                                LocalDate.of(2026, 8, 25), null, null)
                )
        );

        // create - counts derived from the children, status forced to SCHEDULED
        InspectionDto created = service.create(createReq);
        assertThat(created.status()).isEqualTo(InspectionStatus.SCHEDULED);
        // category omitted on the request, so it is derived from the type
        assertThat(created.category()).isEqualTo(InspectionCategory.QA_QC);
        assertThat(created.trade()).isEqualTo(InspectionTrade.RCC);
        assertThat(created.result()).isNull();
        assertThat(created.inspectionNumber()).startsWith("INSP-");
        assertThat(created.checkItems()).hasSize(3);
        assertThat(created.defects()).hasSize(1);
        assertThat(created.defects().getFirst().severity()).isEqualTo(DefectSeverity.MINOR);
        // status omitted on the request, so it takes the OPEN default
        assertThat(created.defects().getFirst().status()).isEqualTo(DefectStatus.OPEN);
        assertThat(created.attendees()).containsExactly("Site Engineer", "Safety Officer");
        // measurement 3mm against an expected 5mm, in the same unit, so the deviation is -2
        assertThat(created.checkItems().getFirst().deviation()).isEqualByComparingTo("-2");
        // no measurement recorded, so there is nothing to deviate from
        assertThat(created.checkItems().get(1).deviation()).isNull();
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
        assertThat(service.findAll(null, null, null, null, null, null, pageable).getTotalElements())
                .isZero();
        assertThat(inspectionRepo.findByIdScoped(id)).isEmpty();

        // visible and listable to the owning organization
        disableOrgFilter();
        enableOrgFilter(orgAId);
        assertThat(service.findAll(null, null, null, null, null, null, pageable).getTotalElements())
                .isEqualTo(1);
        assertThat(service.findAll(projectId, InspectionStatus.SCHEDULED,
                InspectionType.QUALITY, null, null, null, pageable).getTotalElements()).isEqualTo(1);
        // the taxonomy filters narrow on the derived category and the stated trade
        assertThat(service.findAll(null, null, null, InspectionCategory.QA_QC,
                InspectionTrade.RCC, null, pageable).getTotalElements()).isEqualTo(1);
        assertThat(service.findAll(null, null, null, InspectionCategory.SAFETY,
                null, null, pageable).getTotalElements()).isZero();
        assertThat(inspectionRepo.findByIdScoped(id)).isPresent();
        disableOrgFilter();

        // update - status and result set directly, children rebuilt, counts recomputed
        UpdateInspectionRequest updateReq = new UpdateInspectionRequest(
                "Third floor slab check",
                InspectionType.QUALITY,
                InspectionCategory.QA_QC,
                InspectionTrade.RCC,
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
                                null, null, null, null, null, "high")
                ),
                List.of()
        );

        InspectionDto updated = service.update(id, updateReq);
        assertThat(updated.status()).isEqualTo(InspectionStatus.COMPLETED);
        assertThat(updated.category()).isEqualTo(InspectionCategory.QA_QC);
        assertThat(updated.result()).isEqualTo(InspectionResult.PASSED);
        assertThat(updated.checkItems()).hasSize(1);
        assertThat(updated.defects()).isEmpty();
        assertThat(updated.attendees()).containsExactly("Site Engineer");
        assertThat(updated.totalCheckPoints()).isEqualTo(1);
        assertThat(updated.passedCheckPoints()).isEqualTo(1);
        assertThat(updated.failedCheckPoints()).isZero();
        assertThat(updated.defectsFound()).isZero();
    }

    @Test
    void create_startsFromTheTradeChecklistTemplateWhenNoCheckItemsAreSupplied() {
        templateService.create(new ChecklistTemplateRequest(
                InspectionTrade.MASONRY,
                "Masonry checklist",
                "Block work",
                null,
                List.of(
                        new ChecklistTemplateItemRequest("Coursing", "Course height uniform",
                                "IS 2212", "200 mm", "Level taken at both ends", "+/- 5 mm",
                                true, "high"),
                        new ChecklistTemplateItemRequest("Joints", "Mortar joints fully filled",
                                "IS 2212", "10 mm", "No unfilled vertical joint", "+/- 2 mm",
                                false, "medium"))));

        InspectionDto created = service.create(scheduleFor(InspectionTrade.MASONRY, null));

        assertThat(created.checkItems()).hasSize(2);
        assertThat(created.totalCheckPoints()).isEqualTo(2);
        assertThat(created.passedCheckPoints()).isZero();
        assertThat(created.failedCheckPoints()).isZero();

        InspectionCheckItemDto first = created.checkItems().getFirst();
        assertThat(first.checkPoint()).isEqualTo("Course height uniform");
        assertThat(first.acceptanceCriterion()).isEqualTo("Level taken at both ends");
        assertThat(first.tolerance()).isEqualTo("+/- 5 mm");
        assertThat(first.expectedValue()).isEqualTo("200 mm");
        assertThat(first.photosRequired()).isTrue();
        // instantiated items are unanswered: an inspector fills these in on site
        assertThat(first.status()).isEqualTo(CheckItemStatus.PENDING);
        assertThat(first.measurement()).isNull();
        assertThat(first.deviation()).isNull();
        assertThat(created.checkItems().get(1).checkPoint()).isEqualTo("Mortar joints fully filled");
    }

    @Test
    void create_keepsTheSuppliedCheckItemsInsteadOfAppendingTheTemplate() {
        templateService.create(new ChecklistTemplateRequest(
                InspectionTrade.MASONRY,
                "Masonry checklist",
                null,
                null,
                List.of(new ChecklistTemplateItemRequest("Coursing", "Course height uniform",
                        null, null, null, null, false, null))));

        InspectionDto created = service.create(scheduleFor(InspectionTrade.MASONRY,
                List.of(new InspectionCheckItemRequest("Joints", "Re-check the failed joint",
                        null, CheckItemStatus.PENDING, null, false, null,
                        null, null, null, null, null, "high"))));

        assertThat(created.checkItems()).hasSize(1);
        assertThat(created.checkItems().getFirst().checkPoint()).isEqualTo("Re-check the failed joint");
        assertThat(created.totalCheckPoints()).isEqualTo(1);
    }

    @Test
    void create_leavesTheChecklistEmptyWhenTheTradeHasNoActiveTemplate() {
        InspectionDto created = service.create(scheduleFor(InspectionTrade.PLASTERING, null));

        assertThat(created.checkItems()).isEmpty();
        assertThat(created.totalCheckPoints()).isZero();
    }

    @Test
    void update_refusesAStatusMoveThatIsNotPartOfTheLifecycle() {
        InspectionDto created = service.create(scheduleFor(InspectionTrade.PLASTERING, null));
        UUID id = created.id();

        // scheduled straight to passed is legal: work is often carried out and recorded
        // afterwards, so this is a normal day rather than a skipped step
        assertThat(service.update(id, concludeAs(InspectionStatus.PASSED)).status())
                .isEqualTo(InspectionStatus.PASSED);

        // coming back out of that verdict is not
        assertThatThrownBy(() -> service.update(id, concludeAs(InspectionStatus.SCHEDULED)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("is passed and cannot move to scheduled")
                .hasMessageContaining("this is where the inspection ends");

        // and the refusal leaves the stored record untouched
        assertThat(service.findById(id).status()).isEqualTo(InspectionStatus.PASSED);
    }

    @Test
    void update_acceptsAPayloadThatRepeatsTheStoredStatus() {
        // the web client sends the whole record back on every save, so an unchanged
        // status must not be read as an attempted transition
        UUID id = service.create(scheduleFor(InspectionTrade.PLASTERING, null)).id();

        assertThat(service.update(id, concludeAs(InspectionStatus.SCHEDULED)).status())
                .isEqualTo(InspectionStatus.SCHEDULED);
    }

    private UpdateInspectionRequest concludeAs(InspectionStatus status) {
        return new UpdateInspectionRequest(
                "Wall check", InspectionType.QUALITY, null, InspectionTrade.PLASTERING,
                status, null, projectId, "Block A", null, null,
                LocalDate.of(2026, 8, 20), null, null, null, null, 100L, null, null,
                null, null, null, null, null);
    }

    private CreateInspectionRequest scheduleFor(InspectionTrade trade,
                                                List<InspectionCheckItemRequest> checkItems) {
        return new CreateInspectionRequest(
                "Wall check", InspectionType.QUALITY, null, trade, projectId,
                "Block A", null, null, LocalDate.of(2026, 8, 20), null,
                null, null, null, 100L, null, null, null, null, null,
                checkItems, null);
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
