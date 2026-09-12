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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.inspections.InspectionType;
import org.tornotron.echno_backend.modules.inspections.domain.Inspection;
import org.tornotron.echno_backend.modules.inspections.dtos.CreateInspectionRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionDto;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionEventDto;
import org.tornotron.echno_backend.modules.inspections.mapper.ChecklistTemplateMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.TradeMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.DefectPhotoAnnotationMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.InspectionMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.NcrMapperImpl;
import org.tornotron.echno_backend.modules.inspections.repositories.InspectionRepository;
import org.tornotron.echno_backend.modules.inspections.service.ChecklistTemplateService;
import org.tornotron.echno_backend.modules.inspections.service.TradeService;
import org.tornotron.echno_backend.modules.inspections.service.DefectAnnotationService;
import org.tornotron.echno_backend.modules.inspections.service.InspectionService;
import org.tornotron.echno_backend.modules.inspections.service.NcrService;
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
 * Creating an inspection through the service already writes {@code inspection.created}, so
 * every timeline here starts with that row and the assertions count from there.
 *
 * <p>The event log against a real CockroachDB: an event carries its actor and its before and
 * after, lives and dies with the transaction that made the change, is invisible to another
 * tenant, and cannot be rewritten once committed.
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
        NcrService.class, NcrMapperImpl.class,
        InspectionEventRecorder.class, InspectionEventService.class,
        DefectAnnotationService.class, DefectPhotoAnnotationMapperImpl.class,
        UserContextService.class,
        TenantEntityHelper.class, EntryNumberGenerator.class})
class InspectionEventRecorderIT extends AbstractIntegrationTest {

    @Autowired
    private InspectionEventRecorder recorder;

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
            Organization orgA = persistOrganization("Event Org A");
            Organization orgB = persistOrganization("Event Org B");
            Project project = new Project();
            project.setProjectName("Tower E");
            project.setOrganization(orgA);
            entityManager.persist(project);
            persistUser("kc-evt-spare", "Spare");
            Employee qa = persistEmployee(orgA, "kc-evt-qa", "QA Lead E");
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
            deleteForOrgs("DELETE FROM inspection_check_items WHERE inspection_id IN "
                    + "(SELECT id FROM inspections WHERE organization_id IN (:a,:b))");
            deleteForOrgs("DELETE FROM inspections WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM document_sequence WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM project WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM employee WHERE organization_id IN (:a,:b)");
            entityManager.createNativeQuery(
                            "DELETE FROM users_table WHERE keycloak_id IN ('kc-evt-qa','kc-evt-spare')")
                    .executeUpdate();
            deleteForOrgs("DELETE FROM inspection_trades WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM organization WHERE id IN (:a,:b)");
        });
    }

    @Test
    void record_writesTheActorAndTheBeforeAndAfterOfTheChange() {
        authenticateAs("kc-evt-qa");
        Inspection inspection = scheduledInspection();

        recorder.record(InspectionEventSubject.inspection(inspection),
                InspectionEventType.INSPECTION_STATUS_CHANGED,
                Map.of("status", "scheduled"), Map.of("status", "in-progress"), "Started on site");
        entityManager.flush();
        entityManager.clear();

        Page<InspectionEventDto> timeline = events.inspectionTimeline(inspection.getId(), PageRequest.of(0, 10));

        assertThat(timeline.getTotalElements()).isEqualTo(2);
        InspectionEventDto event = timeline.getContent().getLast();
        assertThat(event.eventType()).isEqualTo("inspection.status.changed");
        assertThat(event.subjectType()).isEqualTo(InspectionEventSubjectType.INSPECTION);
        assertThat(event.subjectId()).isEqualTo(inspection.getId());
        assertThat(event.inspectionId()).isEqualTo(inspection.getId());
        assertThat(event.projectId()).isEqualTo(projectId);
        assertThat(event.actorType()).isEqualTo(InspectionEventActorType.USER);
        assertThat(event.actorId()).isEqualTo(String.valueOf(qaEmployeeId));
        assertThat(event.before()).containsEntry("status", "scheduled");
        assertThat(event.after()).containsEntry("status", "in-progress");
        assertThat(event.note()).isEqualTo("Started on site");
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    void record_withoutAnAuthenticatedCaller_isBySystem() {
        Inspection inspection = scheduledInspection();

        recorder.record(InspectionEventSubject.inspection(inspection),
                InspectionEventType.INSPECTION_CREATED, null, Map.of("status", "scheduled"), null);
        entityManager.flush();
        entityManager.clear();

        InspectionEventDto event = events.inspectionTimeline(inspection.getId(), PageRequest.of(0, 10))
                .getContent().getLast();
        assertThat(event.actorType()).isEqualTo(InspectionEventActorType.SYSTEM);
        assertThat(event.actorId()).isNull();
        assertThat(event.before()).isNull();
    }

    @Test
    void recordAs_namesTheMachineActor() {
        Inspection inspection = scheduledInspection();

        recorder.recordAs(InspectionEventSubject.inspection(inspection),
                InspectionEventType.INSPECTION_GENERATED, InspectionEventActorType.AI,
                "compliance-generator", null, Map.of("origin", "ai-generated"), null);
        entityManager.flush();
        entityManager.clear();

        InspectionEventDto event = events.inspectionTimeline(inspection.getId(), PageRequest.of(0, 10))
                .getContent().getLast();
        assertThat(event.actorType()).isEqualTo(InspectionEventActorType.AI);
        assertThat(event.actorId()).isEqualTo("compliance-generator");
    }

    @Test
    void timeline_isOldestFirstAndIncludesEveryChildOfTheInspection() {
        Inspection inspection = scheduledInspection();
        pause();
        UUID itemId = UUID.randomUUID();
        InspectionEventSubject item = new InspectionEventSubject(
                InspectionEventSubjectType.CHECK_ITEM, itemId, inspection.getId(), projectId);

        recorder.record(InspectionEventSubject.inspection(inspection),
                InspectionEventType.INSPECTION_CREATED, null, Map.of("status", "scheduled"), null);
        pause();
        recorder.record(item, InspectionEventType.CHECK_ITEM_RESULT_RECORDED,
                Map.of("status", "pending"), Map.of("status", "failed"), null);
        pause();
        recorder.record(InspectionEventSubject.inspection(inspection),
                InspectionEventType.INSPECTION_STATUS_CHANGED,
                Map.of("status", "scheduled"), Map.of("status", "in-progress"), null);
        entityManager.flush();
        entityManager.clear();

        List<InspectionEventDto> timeline = events
                .inspectionTimeline(inspection.getId(), PageRequest.of(0, 10)).getContent();

        assertThat(timeline).extracting(InspectionEventDto::eventType).containsExactly(
                "inspection.created", "inspection.created", "check_item.result.recorded",
                "inspection.status.changed");
        assertThat(timeline.get(2).subjectId()).isEqualTo(itemId);
    }

    @Test
    void search_narrowsByProjectSubjectTypeEventTypeAndActor() {
        authenticateAs("kc-evt-qa");
        Inspection inspection = scheduledInspection();
        recorder.record(InspectionEventSubject.inspection(inspection),
                InspectionEventType.INSPECTION_CREATED, null, Map.of("status", "scheduled"), null);
        recorder.recordAs(new InspectionEventSubject(InspectionEventSubjectType.DEFECT,
                        UUID.randomUUID(), inspection.getId(), projectId),
                InspectionEventType.DEFECT_CREATED, InspectionEventActorType.DEVICE, "cam-7",
                null, Map.of("severity", "major"), null);
        entityManager.flush();
        entityManager.clear();

        assertThat(events.search(projectId, null, null, null, null, null, PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(3);
        assertThat(events.search(projectId, InspectionEventSubjectType.DEFECT, null, null, null, null,
                PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
        assertThat(events.search(projectId, null, "inspection.created", null, null, null,
                PageRequest.of(0, 10)).getTotalElements()).isEqualTo(2);
        assertThat(events.search(projectId, null, null, "cam-7", null, null,
                PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
        assertThat(events.search(projectId, null, null, null, LocalDateTime.now().plusDays(1), null,
                PageRequest.of(0, 10)).getTotalElements()).isZero();
        assertThat(events.search(projectId + 1, null, null, null, null, null,
                PageRequest.of(0, 10)).getTotalElements()).isZero();
    }

    @Test
    void events_areInvisibleToAnotherTenant() {
        Inspection inspection = scheduledInspection();
        recorder.record(InspectionEventSubject.inspection(inspection),
                InspectionEventType.INSPECTION_CREATED, null, Map.of("status", "scheduled"), null);
        entityManager.flush();
        entityManager.clear();

        TenantContext.setCurrentOrgId(orgBId);

        assertThatThrownBy(() -> events.inspectionTimeline(inspection.getId(), PageRequest.of(0, 10)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(events.search(projectId, null, null, null, null, null, PageRequest.of(0, 10))
                .getTotalElements()).isZero();

        TenantContext.setCurrentOrgId(orgAId);
        assertThat(events.search(projectId, null, null, null, null, null, PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(2);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void record_outsideATransaction_isRefused() {
        InspectionEventSubject subject = new InspectionEventSubject(
                InspectionEventSubjectType.INSPECTION, UUID.randomUUID(), UUID.randomUUID(), projectId);

        assertThatThrownBy(() -> recorder.record(subject, InspectionEventType.INSPECTION_CREATED,
                null, Map.of("status", "scheduled"), null))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void record_inATransactionThatFails_leavesNoEvent() {
        InspectionEventSubject subject = new InspectionEventSubject(
                InspectionEventSubjectType.INSPECTION, UUID.randomUUID(), UUID.randomUUID(), projectId);

        assertThatThrownBy(() -> inCommittedTx(() -> {
            TenantContext.setCurrentOrgId(orgAId);
            recorder.record(subject, InspectionEventType.INSPECTION_CREATED,
                    null, Map.of("status", "scheduled"), null);
            entityManager.flush();
            throw new IllegalStateException("the change itself failed");
        })).isInstanceOf(IllegalStateException.class);

        inCommittedTx(() -> {
            TenantContext.setCurrentOrgId(orgAId);
            assertThat(events.search(projectId, null, null, null, null, null, PageRequest.of(0, 10))
                    .getTotalElements()).isZero();
        });
    }

    @Test
    void events_cannotBeRewrittenByABulkUpdate() {
        Inspection inspection = scheduledInspection();
        recorder.record(InspectionEventSubject.inspection(inspection),
                InspectionEventType.INSPECTION_CREATED, null, Map.of("status", "scheduled"), null);
        entityManager.flush();

        assertThatThrownBy(() -> entityManager
                .createQuery("UPDATE InspectionEvent e SET e.note = 'rewritten' WHERE e.inspectionId = :id")
                .setParameter("id", inspection.getId())
                .executeUpdate())
                .hasMessageContaining("immutable");
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
