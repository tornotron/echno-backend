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
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.inspection.dtos.AssignNcrRequest;
import org.tornotron.echno_backend.inspection.dtos.CreateInspectionRequest;
import org.tornotron.echno_backend.inspection.dtos.CreateNcrRequest;
import org.tornotron.echno_backend.inspection.dtos.InspectionDefectRequest;
import org.tornotron.echno_backend.inspection.dtos.InspectionDto;
import org.tornotron.echno_backend.inspection.dtos.NcrDto;
import org.tornotron.echno_backend.inspection.mapper.ChecklistTemplateMapperImpl;
import org.tornotron.echno_backend.inspection.mapper.InspectionMapperImpl;
import org.tornotron.echno_backend.inspection.mapper.DefectPhotoAnnotationMapperImpl;
import org.tornotron.echno_backend.inspection.mapper.NcrMapperImpl;
import org.tornotron.echno_backend.inspection.service.ChecklistTemplateService;
import org.tornotron.echno_backend.inspection.service.DefectAnnotationService;
import org.tornotron.echno_backend.inspection.service.InspectionService;
import org.tornotron.echno_backend.inspection.service.NcrService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserContextService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The non-conformance workflow against a real CockroachDB: the trail an NCR leaves
 * as it is raised, assigned, reported complete, re-inspected and closed, and the
 * moves it refuses. The refusals are the point of the entity: an NCR that could be
 * closed straight from open would make the closure trail a record of nothing.
 *
 * <p>The annotations and the {@code @Import} list repeat {@link InspectionServiceIT}
 * to the letter on purpose, so Spring's context cache serves all three inspection
 * test classes from one context. See that class for why that matters in a 1 GB test
 * JVM. Keep them in step when any one changes.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({InspectionService.class, InspectionMapperImpl.class,
        ChecklistTemplateService.class, ChecklistTemplateMapperImpl.class,
        NcrService.class, NcrMapperImpl.class,
        DefectAnnotationService.class, DefectPhotoAnnotationMapperImpl.class,
        UserContextService.class,
        TenantEntityHelper.class, EntryNumberGenerator.class})
class NcrServiceIT extends AbstractIntegrationTest {

    @Autowired
    private NcrService service;

    @Autowired
    private InspectionService inspectionService;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private Long orgAId;
    private Long orgBId;
    private Long projectId;
    private Long siteEngineerId;
    private Long foreignSiteEngineerId;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        inCommittedTx(() -> {
            Organization orgA = persistOrganization("NCR Org A");
            Organization orgB = persistOrganization("NCR Org B");

            Project project = new Project();
            project.setProjectName("Tower B");
            project.setOrganization(orgA);
            entityManager.persist(project);

            Employee siteEngineer = persistEmployee(orgA, "kc-ncr-a", "Site Engineer A");
            Employee otherOrgEngineer = persistEmployee(orgB, "kc-ncr-b", "Site Engineer B");

            entityManager.flush();
            orgAId = orgA.getId();
            orgBId = orgB.getId();
            projectId = project.getId();
            siteEngineerId = siteEngineer.getId();
            foreignSiteEngineerId = otherOrgEngineer.getId();
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
        inCommittedTx(() -> {
            deleteForOrgs("DELETE FROM ncrs WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM inspection_defects WHERE inspection_id IN "
                    + "(SELECT id FROM inspections WHERE organization_id IN (:a,:b))");
            deleteForOrgs("DELETE FROM inspection_check_items WHERE inspection_id IN "
                    + "(SELECT id FROM inspections WHERE organization_id IN (:a,:b))");
            deleteForOrgs("DELETE FROM inspections WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM document_sequence WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM project WHERE organization_id IN (:a,:b)");
            // Employees before their users, and users last: the FK runs that way and
            // the users are not org-scoped, so they are named by the ids seeded above.
            deleteForOrgs("DELETE FROM employee WHERE organization_id IN (:a,:b)");
            entityManager.createNativeQuery(
                            "DELETE FROM users_table WHERE keycloak_id IN ('kc-ncr-a','kc-ncr-b')")
                    .executeUpdate();
            deleteForOrgs("DELETE FROM organization WHERE id IN (:a,:b)");
        });
    }

    @Test
    void create_takesItsTypeAndNumberFromTheInspectionItWasRaisedFrom() {
        InspectionDto inspection = qualityInspection();

        NcrDto raised = service.create(new CreateNcrRequest(inspection.id(), null,
                "Cover below specification", "Measured 25 mm against a specified 40 mm",
                DefectSeverity.MAJOR, null, LocalDate.of(2026, 9, 10)));

        assertThat(raised.ncrNumber()).startsWith("NCR-");
        // the type comes from the inspection's category, never from the request
        assertThat(raised.type()).isEqualTo(NcrType.QUALITY);
        assertThat(raised.status()).isEqualTo(NcrStatus.OPEN);
        assertThat(raised.inspectionId()).isEqualTo(inspection.id());
        assertThat(raised.siteEngineerId()).isNull();
        assertThat(raised.closedAt()).isNull();
    }

    @Test
    void create_raisesASafetyNcrFromASafetyInspection() {
        InspectionDto inspection = inspectionOf(InspectionType.SAFETY);

        NcrDto raised = service.create(new CreateNcrRequest(inspection.id(), null,
                "Edge protection missing", "Open edge on level 4 with no barrier",
                DefectSeverity.CRITICAL, null, null));

        assertThat(raised.type()).isEqualTo(NcrType.SAFETY);
    }

    @Test
    void create_assignsInTheSameStepWhenASiteEngineerIsNamed() {
        InspectionDto inspection = qualityInspection();

        NcrDto raised = service.create(new CreateNcrRequest(inspection.id(), null,
                "Cover below specification", "Measured 25 mm", DefectSeverity.MAJOR,
                siteEngineerId, LocalDate.of(2026, 9, 10)));

        assertThat(raised.status()).isEqualTo(NcrStatus.ASSIGNED);
        assertThat(raised.siteEngineerId()).isEqualTo(siteEngineerId);
        assertThat(raised.targetDate()).isEqualTo(LocalDate.of(2026, 9, 10));
    }

    @Test
    void create_refusesADefectThatBelongsToAnotherInspection() {
        InspectionDto withDefect = qualityInspection();
        UUID foreignDefectId = withDefect.defects().getFirst().id();
        InspectionDto other = inspectionOf(InspectionType.QUALITY);

        assertThatThrownBy(() -> service.create(new CreateNcrRequest(other.id(), foreignDefectId,
                "Wrong defect", "Points at another inspection's defect", null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("was not found on inspection");
    }

    @Test
    void create_refusesAnInspectionThatIsNotInThisTenant() {
        UUID unknown = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

        assertThatThrownBy(() -> service.create(new CreateNcrRequest(unknown, null,
                "No inspection", "Nothing to raise this against", null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void create_refusesAnAssigneeThisOrganizationDoesNotHave() {
        InspectionDto inspection = qualityInspection();

        // nobody at all
        assertThatThrownBy(() -> service.create(new CreateNcrRequest(inspection.id(), null,
                "Cover below specification", "Measured 25 mm", null, 999_999L, null)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("cannot be assigned a non-conformance");

        // and somebody real, in another tenant: an NCR owned by them would be open
        // against a person who will never see it
        assertThatThrownBy(() -> service.create(new CreateNcrRequest(inspection.id(), null,
                "Cover below specification", "Measured 25 mm", null, foreignSiteEngineerId, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void assign_refusesAnAssigneeThisOrganizationDoesNotHave() {
        InspectionDto inspection = qualityInspection();
        UUID id = service.create(new CreateNcrRequest(inspection.id(), null,
                "Cover below specification", "Measured 25 mm", null, null, null)).id();

        assertThatThrownBy(() -> service.assign(id, new AssignNcrRequest(foreignSiteEngineerId, null)))
                .isInstanceOf(ResourceNotFoundException.class);

        // and the refusal leaves the report where it was, unowned
        assertThat(service.findById(id).status()).isEqualTo(NcrStatus.OPEN);
        assertThat(service.findById(id).siteEngineerId()).isNull();
    }

    @Test
    void theWholeTrailFromRaisedToClosed() {
        InspectionDto inspection = qualityInspection();
        UUID id = service.create(new CreateNcrRequest(inspection.id(),
                inspection.defects().getFirst().id(),
                "Honeycombing on column C4", "Chip out and re-pour", DefectSeverity.MAJOR,
                null, null)).id();

        NcrDto assigned = service.assign(id,
                new AssignNcrRequest(siteEngineerId, LocalDate.of(2026, 9, 5)));
        assertThat(assigned.status()).isEqualTo(NcrStatus.ASSIGNED);
        assertThat(assigned.siteEngineerId()).isEqualTo(siteEngineerId);

        NcrDto reported = service.markCorrectiveActionComplete(id, "Re-poured on 5 September");
        assertThat(reported.status()).isEqualTo(NcrStatus.CORRECTIVE_ACTION_COMPLETE);
        assertThat(reported.correctiveActionRemarks()).isEqualTo("Re-poured on 5 September");
        assertThat(reported.correctiveActionCompletedAt()).isNotNull();
        // reporting the work done is not accepting it
        assertThat(reported.closedAt()).isNull();

        NcrDto verified = service.verify(id, "Re-inspected, cover now 42 mm");
        assertThat(verified.status()).isEqualTo(NcrStatus.VERIFIED);
        assertThat(verified.verifiedAt()).isNotNull();
        assertThat(verified.closedAt()).isNull();

        NcrDto closed = service.close(id);
        assertThat(closed.status()).isEqualTo(NcrStatus.CLOSED);
        assertThat(closed.closedAt()).isNotNull();
    }

    @Test
    void rejectedWorkGoesBackToTheSiteEngineer() {
        UUID id = assignedNcr();

        service.markCorrectiveActionComplete(id, "Done");
        NcrDto rejected = service.reject(id, "Cover still short at grid C5");
        assertThat(rejected.status()).isEqualTo(NcrStatus.REJECTED);
        assertThat(rejected.verificationRemarks()).isEqualTo("Cover still short at grid C5");

        NcrDto reassigned = service.assign(id, new AssignNcrRequest(siteEngineerId, null));
        assertThat(reassigned.status()).isEqualTo(NcrStatus.ASSIGNED);
        assertThat(reassigned.siteEngineerId()).isEqualTo(siteEngineerId);
    }

    /**
     * A rejection is a re-inspection decision, so it stamps the same fields an
     * acceptance does. Without the time, a rejected report renders a re-inspector
     * and no date; worse, on a report that had already been accepted and reopened
     * once, it renders the rejection against the date of that earlier acceptance.
     */
    @Test
    void aRejectionIsStampedLikeTheAcceptanceItRefuses() {
        UUID id = assignedNcr();
        service.markCorrectiveActionComplete(id, "Done");

        NcrDto accepted = service.verify(id, "Accepted");
        LocalDateTime acceptedAt = accepted.verifiedAt();
        assertThat(acceptedAt).isNotNull();

        service.reopen(id, "Same honeycombing found again");
        service.assign(id, new AssignNcrRequest(siteEngineerId, null));
        service.markCorrectiveActionComplete(id, "Done again");
        NcrDto rejected = service.reject(id, "Cover still short at grid C5");

        assertThat(rejected.verifiedAt())
                .as("the rejection carries its own time, not the earlier acceptance's")
                .isNotNull()
                .isAfter(acceptedAt);
    }

    @Test
    void reopeningAClosedReportClearsItsClosure() {
        UUID id = assignedNcr();
        service.markCorrectiveActionComplete(id, "Done");
        service.verify(id, "Accepted");
        NcrDto closed = service.close(id);
        assertThat(closed.status()).isEqualTo(NcrStatus.CLOSED);

        NcrDto reopened = service.reopen(id, "Same honeycombing found again on 20 September");
        assertThat(reopened.status()).isEqualTo(NcrStatus.REOPENED);
        // the closure is undone rather than left standing next to an open report
        assertThat(reopened.closedAt()).isNull();
        assertThat(reopened.closedById()).isNull();
    }

    @Test
    void refusesToCloseAReportThatWasNeverWorkedOn() {
        InspectionDto inspection = qualityInspection();
        UUID id = service.create(new CreateNcrRequest(inspection.id(), null,
                "Cover below specification", "Measured 25 mm", null, null, null)).id();

        assertThatThrownBy(() -> service.close(id))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("is open and cannot move to closed")
                .hasMessageContaining("it may move to assigned");

        assertThat(service.findById(id).status()).isEqualTo(NcrStatus.OPEN);
        assertThat(service.findById(id).closedAt()).isNull();
    }

    @Test
    void refusesToVerifyWorkThatWasNeverReportedComplete() {
        UUID id = assignedNcr();

        assertThatThrownBy(() -> service.verify(id, "Looks fine"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("is assigned and cannot move to verified");
    }

    @Test
    void listsThePunchListAndScopesEverythingToTheTenant() {
        InspectionDto inspection = qualityInspection();
        UUID open = service.create(new CreateNcrRequest(inspection.id(), null,
                "Still outstanding", "Not done", null, siteEngineerId, null)).id();
        UUID done = service.create(new CreateNcrRequest(inspection.id(), null,
                "Settled", "Sorted", null, siteEngineerId, null)).id();
        service.markCorrectiveActionComplete(done, "Done");
        service.verify(done, "Accepted");
        service.close(done);

        entityManager.flush();
        entityManager.clear();
        Pageable pageable = PageRequest.of(0, 10);

        assertThat(service.findAll(null, null, null, null, true, pageable).getContent())
                .extracting(NcrDto::id).containsExactly(open);
        assertThat(service.findAll(null, null, null, null, false, pageable).getContent())
                .extracting(NcrDto::id).containsExactly(done);
        assertThat(service.findAll(null, NcrType.QUALITY, null, siteEngineerId, null, pageable)
                .getTotalElements()).isEqualTo(2);
        assertThat(service.findAll(null, NcrType.SAFETY, null, null, null, pageable)
                .getTotalElements()).isZero();

        enableOrgFilter(orgBId);
        assertThat(service.findAll(null, null, null, null, null, pageable).getTotalElements())
                .isZero();
        assertThatThrownBy(() -> service.findById(open))
                .isInstanceOf(ResourceNotFoundException.class);
        disableOrgFilter();
    }

    private UUID assignedNcr() {
        InspectionDto inspection = qualityInspection();
        return service.create(new CreateNcrRequest(inspection.id(), null,
                "Honeycombing on column C4", "Chip out and re-pour", DefectSeverity.MAJOR,
                siteEngineerId, null)).id();
    }

    private InspectionDto qualityInspection() {
        return inspectionService.create(new CreateInspectionRequest(
                "Slab check", InspectionType.QUALITY, null, null, projectId,
                "Block A", null, null, LocalDate.of(2026, 8, 20), null,
                null, null, null, 100L, null, null, null, null, null,
                null,
                List.of(new InspectionDefectRequest("Structural", "Honeycombing on column C4",
                        DefectSeverity.MAJOR, "Grid C4", null, "Chip out and re-pour",
                        "Contractor", LocalDate.of(2026, 9, 1), null, null))));
    }

    private InspectionDto inspectionOf(InspectionType type) {
        return inspectionService.create(new CreateInspectionRequest(
                "Site check", type, null, null, projectId,
                "Block A", null, null, LocalDate.of(2026, 8, 20), null,
                null, null, null, 100L, null, null, null, null, null,
                null, null));
    }

    private void enableOrgFilter(Long orgId) {
        entityManager.unwrap(Session.class)
                .enableFilter("orgFilter")
                .setParameter("organizationId", orgId);
    }

    private void disableOrgFilter() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
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

    private Employee persistEmployee(Organization org, String keycloakId, String name) {
        User user = new User();
        user.setKeycloakId(keycloakId);
        user.setName(name);
        entityManager.persist(user);

        Employee employee = new Employee();
        employee.setOrganization(org);
        employee.setUser(user);
        employee.setEmployeeName(name);
        employee.setGender("U");
        employee.setPhoneNumber("0000000000");
        employee.setEmailAddress(keycloakId + "@example.test");
        employee.setDateOfBirth(LocalDateTime.of(1990, 1, 1, 0, 0));
        entityManager.persist(employee);
        return employee;
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
