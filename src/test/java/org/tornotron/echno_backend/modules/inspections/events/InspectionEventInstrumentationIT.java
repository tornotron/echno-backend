package org.tornotron.echno_backend.modules.inspections.events;

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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.inspections.InspectionType;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.modules.inspections.dtos.UpdateInspectionRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.NcrDto;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionDefectRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionCheckItemRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.CreateNcrRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.AssignNcrRequest;
import org.tornotron.echno_backend.modules.inspections.NcrStatus;
import org.tornotron.echno_backend.modules.inspections.InspectionStatus;
import org.tornotron.echno_backend.modules.inspections.InspectionResult;
import org.tornotron.echno_backend.modules.inspections.InspectionCategory;
import org.tornotron.echno_backend.modules.inspections.DefectStatus;
import org.tornotron.echno_backend.modules.inspections.DefectSeverity;
import org.tornotron.echno_backend.modules.inspections.CheckItemStatus;
import org.tornotron.echno_backend.modules.inspections.domain.Inspection;
import org.tornotron.echno_backend.modules.inspections.dtos.CreateInspectionRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionDto;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionEventDto;
import org.tornotron.echno_backend.modules.inspections.mapper.ChecklistTemplateMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.TradeMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.ElementTypeMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.DefectPhotoAnnotationMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.InspectionMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.NcrMapperImpl;
import org.tornotron.echno_backend.modules.inspections.repositories.InspectionRepository;
import org.tornotron.echno_backend.modules.inspections.service.ChecklistTemplateService;
import org.tornotron.echno_backend.modules.inspections.service.TradeService;
import org.tornotron.echno_backend.modules.inspections.service.ElementTypeService;
import org.tornotron.echno_backend.modules.inspections.service.DefectAnnotationService;
import org.tornotron.echno_backend.modules.inspections.service.InspectionService;
import org.tornotron.echno_backend.modules.inspections.service.NcrService;
import org.tornotron.echno_backend.modules.inspections.service.ObservationService;
import org.tornotron.echno_backend.modules.inspections.mapper.ObservationMapperImpl;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.spatial.SpatialNodeService;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserContextService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every state change the inspection and NCR services make writes its event, with the before
 * and after a reader needs, and a refused change writes none. Each assertion here fails when
 * the recorder call it names is removed from the service.
 *
 * <p>The annotations and the {@code @Import} list repeat {@link
 * org.tornotron.echno_backend.modules.inspections.InspectionServiceIT} to the letter so
 * Spring's context cache serves every inspection test class from one context. Keep them in
 * step when any one changes.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({InspectionService.class, SpatialNodeService.class, InspectionMapperImpl.class,
        ChecklistTemplateService.class, ChecklistTemplateMapperImpl.class,
        TradeService.class, TradeMapperImpl.class,
        ElementTypeService.class, ElementTypeMapperImpl.class,
        NcrService.class, NcrMapperImpl.class,
        ObservationService.class, ObservationMapperImpl.class,
        InspectionEventRecorder.class, InspectionEventService.class,
        DefectAnnotationService.class, DefectPhotoAnnotationMapperImpl.class,
        UserContextService.class,
        TenantEntityHelper.class, EntryNumberGenerator.class})
class InspectionEventInstrumentationIT extends AbstractIntegrationTest {

    @Autowired
    private NcrService ncrService;

    @Autowired
    private InspectionEventService events;

    @Autowired
    private InspectionService inspectionService;

    @Autowired
    private InspectionRepository inspectionRepo;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private Long orgAId;
    private Long orgBId;
    private Long projectId;
    private Long qaEmployeeId;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        inCommittedTx(() -> {
            Organization orgA = persistOrganization("Instr Org A");
            Organization orgB = persistOrganization("Instr Org B");
            Project project = new Project();
            project.setProjectName("Tower I");
            project.setOrganization(orgA);
            entityManager.persist(project);
            persistUser("kc-ins-spare", "Spare");
            Employee qa = persistEmployee(orgA, "kc-ins-qa", "QA Lead E");
            entityManager.flush();
            orgAId = orgA.getId();
            orgBId = orgB.getId();
            projectId = project.getId();
            qaEmployeeId = qa.getId();
        });
        TenantContext.setCurrentOrgId(orgAId);
    }

    @AfterEach
    void clearTenantState() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        // The two tests that run outside a transaction get no @AfterTransaction callback.
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            removeCommittedRows();
        }
    }

    @AfterTransaction
    void removeCommittedRows() {
        if (orgAId == null && orgBId == null) {
            return;
        }
        inCommittedTx(() -> {
            deleteForOrgs("DELETE FROM inspection_events WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM ncrs WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM inspection_defects WHERE inspection_id IN "
                    + "(SELECT id FROM inspections WHERE organization_id IN (:a,:b))");
            deleteForOrgs("DELETE FROM inspection_check_items WHERE inspection_id IN "
                    + "(SELECT id FROM inspections WHERE organization_id IN (:a,:b))");
            deleteForOrgs("DELETE FROM inspection_observations WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM inspections WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM document_sequence WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM project WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM employee WHERE organization_id IN (:a,:b)");
            entityManager.createNativeQuery(
                            "DELETE FROM users_table WHERE keycloak_id IN ('kc-ins-qa','kc-ins-spare')")
                    .executeUpdate();
            deleteForOrgs("DELETE FROM inspection_trades WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM org_element_types WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM organization WHERE id IN (:a,:b)");
        });
    }

    @Test
    void create_recordsTheInspectionItsResultsAndItsDefects() {
        authenticateAs("kc-ins-qa");
        InspectionDto created = inspectionService.create(withChildren());
        entityManager.flush();
        entityManager.clear();

        List<InspectionEventDto> timeline = timeline(created.id());

        assertThat(timeline).extracting(InspectionEventDto::eventType).containsExactly(
                "inspection.created", "check_item.result.recorded", "check_item.result.recorded",
                "defect.created",
                // the failed check point and the defect each get their implicit observation
                "observation.created", "observation.created");
        InspectionEventDto head = timeline.getFirst();
        assertThat(head.actorType()).isEqualTo(InspectionEventActorType.USER);
        assertThat(head.actorId()).isEqualTo(String.valueOf(qaEmployeeId));
        assertThat(head.before()).isNull();
        assertThat(head.after()).containsEntry("status", "scheduled").containsEntry("type", "quality")
                .containsEntry("projectId", projectId.intValue());
        // the pending check point writes nothing; the two with a result write one each
        assertThat(timeline.get(1).subjectType()).isEqualTo(InspectionEventSubjectType.CHECK_ITEM);
        assertThat(timeline.get(1).after()).containsEntry("status", "passed");
        assertThat(timeline.get(2).after()).containsEntry("status", "failed");
        assertThat(timeline.get(3).subjectType()).isEqualTo(InspectionEventSubjectType.DEFECT);
        assertThat(timeline.get(3).subjectId()).isEqualTo(created.defects().getFirst().id());
        assertThat(timeline.get(3).after()).containsEntry("severity", "minor").containsEntry("status", "open");
    }

    @Test
    void update_recordsStatusResultHeaderCheckPointAndDefectChangesSeparately() {
        authenticateAs("kc-ins-qa");
        InspectionDto created = inspectionService.create(withChildren());
        entityManager.flush();
        int already = timeline(created.id()).size();

        inspectionService.update(created.id(), new UpdateInspectionRequest(
                "Slab check, level 3", InspectionType.QUALITY, InspectionCategory.QA_QC, "rcc", null,
                InspectionStatus.COMPLETED, InspectionResult.FAILED, projectId,
                "Block A", null, null, LocalDate.of(2026, 9, 12), null,
                null, null, null, 100L, null, null, null, null, null,
                List.of(
                        new InspectionCheckItemRequest("Structural", "Column alignment",
                                null, CheckItemStatus.PASSED, null, false, null,
                                null, null, null, null, null, "high", null),
                        new InspectionCheckItemRequest("Structural", "Rebar spacing",
                                null, CheckItemStatus.FAILED, "Spacing 180 c/c", false, null,
                                null, null, null, null, null, "medium", null),
                        new InspectionCheckItemRequest("Finishing", "Surface level",
                                null, CheckItemStatus.FAILED, null, false, null,
                                null, null, null, null, null, "low", null)),
                List.of(
                        new InspectionDefectRequest("Finishing", "Uneven surface near grid B2",
                                DefectSeverity.MAJOR, "Grid B2", null, "Re-level and re-finish",
                                "Contractor", LocalDate.of(2026, 9, 20), DefectStatus.IN_PROGRESS, null, null),
                        new InspectionDefectRequest("Structural", "Rebar spacing out of tolerance",
                                DefectSeverity.MAJOR, "Bay 4", null, "Re-tie to 150 c/c",
                                "Contractor", null, null, null, null)), null));
        entityManager.flush();
        entityManager.clear();

        List<InspectionEventDto> fresh = timeline(created.id()).subList(already, timeline(created.id()).size());

        assertThat(fresh).extracting(InspectionEventDto::eventType).containsExactly(
                "inspection.status.changed", "inspection.result.recorded", "inspection.updated",
                "check_item.result.recorded", "check_item.remarks.recorded",
                "defect.status.changed", "defect.updated", "defect.created",
                // the newly failed check point and the new defect each get an observation
                "observation.created", "observation.created");
        assertThat(fresh.get(0).before()).containsEntry("status", "scheduled");
        assertThat(fresh.get(0).after()).containsEntry("status", "completed");
        assertThat(fresh.get(1).after()).containsEntry("result", "failed");
        assertThat(fresh.get(2).before()).containsEntry("title", "Slab check");
        assertThat(fresh.get(2).after()).containsEntry("title", "Slab check, level 3")
                .doesNotContainKey("status");
        // the second check point went pending to failed; the first and third did not move
        assertThat(fresh.get(3).before()).containsEntry("status", "pending");
        assertThat(fresh.get(3).after()).containsEntry("status", "failed");
        assertThat(fresh.get(3).note()).isEqualTo("Rebar spacing");
        assertThat(fresh.get(4).after()).containsEntry("remarks", "Spacing 180 c/c");
        assertThat(fresh.get(5).before()).containsEntry("status", "open");
        assertThat(fresh.get(5).after()).containsEntry("status", "in-progress");
        assertThat(fresh.get(6).before()).containsEntry("severity", "minor");
        assertThat(fresh.get(6).after()).containsEntry("severity", "major").doesNotContainKey("status");
        assertThat(fresh.get(7).after()).containsEntry("description", "Rebar spacing out of tolerance");
    }

    @Test
    void update_thatCancels_isNamedACancellation() {
        Inspection inspection = scheduledInspection();
        entityManager.flush();

        inspectionService.update(inspection.getId(), headerOnly(InspectionStatus.CANCELLED, null));
        entityManager.flush();
        entityManager.clear();

        List<InspectionEventDto> timeline = timeline(inspection.getId());
        assertThat(timeline).extracting(InspectionEventDto::eventType)
                .containsExactly("inspection.created", "inspection.cancelled");
        assertThat(timeline.get(1).after()).containsEntry("status", "cancelled");
    }

    @Test
    void update_thatChangesNothing_writesNothing() {
        Inspection inspection = scheduledInspection();
        entityManager.flush();

        inspectionService.update(inspection.getId(), headerOnly(InspectionStatus.SCHEDULED, null));
        entityManager.flush();
        entityManager.clear();

        assertThat(timeline(inspection.getId())).extracting(InspectionEventDto::eventType)
                .containsExactly("inspection.created");
    }

    @Test
    void update_thatIsRefused_writesNothing() {
        Inspection inspection = scheduledInspection();
        inspectionService.update(inspection.getId(), headerOnly(InspectionStatus.CANCELLED, null));
        entityManager.flush();

        assertThatThrownBy(() -> inspectionService.update(inspection.getId(),
                headerOnly(InspectionStatus.IN_PROGRESS, null)))
                .isInstanceOf(InvalidRequestException.class);
        entityManager.clear();

        assertThat(timeline(inspection.getId())).extracting(InspectionEventDto::eventType)
                .containsExactly("inspection.created", "inspection.cancelled");
    }

    @Test
    void ncrLifecycle_writesOneEventPerTransitionWithBeforeAndAfter() {
        authenticateAs("kc-ins-qa");
        Inspection inspection = scheduledInspection();
        NcrDto ncr = ncrService.create(new CreateNcrRequest(inspection.getId(), null,
                "Cover below specification", "Measured 25 mm", DefectSeverity.MAJOR, null,
                LocalDate.of(2026, 9, 30)));
        ncrService.assign(ncr.id(), new AssignNcrRequest(qaEmployeeId, null));
        ncrService.markCorrectiveActionComplete(ncr.id(), "Re-poured");
        ncrService.reject(ncr.id(), "Honeycombing still visible");
        ncrService.assign(ncr.id(), new AssignNcrRequest(qaEmployeeId, LocalDate.of(2026, 10, 5)));
        ncrService.markCorrectiveActionComplete(ncr.id(), "Grouted and cured");
        ncrService.verify(ncr.id(), "Accepted");
        ncrService.close(ncr.id());
        ncrService.reopen(ncr.id(), "Cracked again");
        entityManager.flush();
        entityManager.clear();

        List<InspectionEventDto> events = ncrTimeline(ncr.id());

        assertThat(events).extracting(InspectionEventDto::eventType).containsExactly(
                "ncr.created", "ncr.assigned", "ncr.corrective_action.complete", "ncr.rejected",
                "ncr.assigned", "ncr.corrective_action.complete", "ncr.verified.without_reinspection",
                "ncr.closed", "ncr.reopened");
        assertThat(events).allSatisfy(e -> {
            assertThat(e.subjectType()).isEqualTo(InspectionEventSubjectType.NCR);
            assertThat(e.subjectId()).isEqualTo(ncr.id());
            assertThat(e.inspectionId()).isEqualTo(inspection.getId());
            assertThat(e.projectId()).isEqualTo(projectId);
            assertThat(e.actorId()).isEqualTo(String.valueOf(qaEmployeeId));
        });
        assertThat(events.get(0).after()).containsEntry("status", "open").containsEntry("type", "quality");
        assertThat(events.get(1).before()).containsEntry("status", "open").containsEntry("siteEngineerId", null);
        assertThat(events.get(1).after()).containsEntry("status", "assigned")
                .containsEntry("siteEngineerId", qaEmployeeId.intValue());
        assertThat(events.get(2).after()).containsEntry("correctiveActionRemarks", "Re-poured");
        assertThat(events.get(3).before()).containsEntry("status", "corrective-action-complete");
        assertThat(events.get(3).after()).containsEntry("status", "rejected")
                .containsEntry("verificationRemarks", "Honeycombing still visible")
                .containsEntry("verifiedById", qaEmployeeId.intValue());
        assertThat(events.get(4).after()).containsEntry("targetDate", "2026-10-05");
        // the same person verifies who rejected, so verifiedById did not move and is left out
        assertThat(events.get(6).after()).containsEntry("status", "verified")
                .containsEntry("verificationRemarks", "Accepted").doesNotContainKey("verifiedById");
        assertThat(events.get(7).after()).containsEntry("status", "closed")
                .containsEntry("closedById", qaEmployeeId.intValue());
        assertThat(events.get(8).before()).containsEntry("status", "closed");
        assertThat(events.get(8).after()).containsEntry("status", "reopened").containsEntry("closedById", null);

        // the NCR's events are part of the inspection's own timeline too
        assertThat(timeline(inspection.getId())).extracting(InspectionEventDto::eventType)
                .contains("ncr.created", "ncr.verified.without_reinspection");
    }

    @Test
    void ncrTransition_thatIsRefused_writesNothing() {
        authenticateAs("kc-ins-qa");
        Inspection inspection = scheduledInspection();
        NcrDto ncr = ncrService.create(new CreateNcrRequest(inspection.getId(), null,
                "Cover below specification", "Measured 25 mm", DefectSeverity.MAJOR, null, null));
        entityManager.flush();

        assertThatThrownBy(() -> ncrService.close(ncr.id())).isInstanceOf(InvalidRequestException.class);
        entityManager.clear();

        assertThat(ncrTimeline(ncr.id())).extracting(InspectionEventDto::eventType)
                .containsExactly("ncr.created");
    }

    private List<InspectionEventDto> timeline(UUID inspectionId) {
        return events.inspectionTimeline(inspectionId, PageRequest.of(0, 50)).getContent();
    }

    private List<InspectionEventDto> ncrTimeline(UUID ncrId) {
        return events.ncrTimeline(ncrId, PageRequest.of(0, 50)).getContent();
    }

    private CreateInspectionRequest withChildren() {
        return new CreateInspectionRequest(
                "Slab check", InspectionType.QUALITY, null, "rcc", null, projectId,
                "Block A", null, null, LocalDate.of(2026, 9, 12), null,
                null, null, null, 100L, null, null, null, null, null,
                List.of(
                        new InspectionCheckItemRequest("Structural", "Column alignment",
                                null, CheckItemStatus.PASSED, null, false, null,
                                null, null, null, null, null, "high", null),
                        new InspectionCheckItemRequest("Structural", "Rebar spacing",
                                null, CheckItemStatus.PENDING, null, false, null,
                                null, null, null, null, null, "medium", null),
                        new InspectionCheckItemRequest("Finishing", "Surface level",
                                null, CheckItemStatus.FAILED, null, false, null,
                                null, null, null, null, null, "low", null)),
                List.of(new InspectionDefectRequest("Finishing", "Uneven surface near grid B2",
                        DefectSeverity.MINOR, "Grid B2", null, "Re-level and re-finish",
                        "Contractor", LocalDate.of(2026, 9, 20), null, null, null)), null);
    }

    private UpdateInspectionRequest headerOnly(InspectionStatus status, InspectionResult result) {
        return new UpdateInspectionRequest(
                "Slab check", InspectionType.QUALITY, null, null, null, status, result, projectId,
                "Block A", null, null, LocalDate.of(2026, 9, 12), null,
                null, null, null, 100L, null, null, null, null, null,
                List.of(), List.of(), null);
    }

    private Inspection scheduledInspection() {
        InspectionDto dto = inspectionService.create(new CreateInspectionRequest(
                "Slab check", InspectionType.QUALITY, null, null, null, projectId,
                "Block A", null, null, LocalDate.of(2026, 9, 12), null,
                null, null, null, 100L, null, null, null, null, null,
                null, null, null));
        return inspectionRepo.findByIdScoped(dto.id()).orElseThrow();
    }

    /** Puts a few milliseconds between two events so occurredAt orders them, as it does in use. */
    private static void pause() {
        try {
            Thread.sleep(3);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void authenticateAs(String keycloakId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(keycloakId, "n/a", AuthorityUtils.NO_AUTHORITIES));
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

    private User persistUser(String keycloakId, String name) {
        User user = new User();
        user.setKeycloakId(keycloakId);
        user.setName(name);
        entityManager.persist(user);
        return user;
    }

    private Employee persistEmployee(Organization org, String keycloakId, String name) {
        User user = persistUser(keycloakId, name);
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
