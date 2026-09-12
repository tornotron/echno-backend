package org.tornotron.echno_backend.modules.inspections;

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
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.modules.inspections.dtos.UpdateInspectionRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.NcrDto;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionDefectRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionCheckItemRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.CreateNcrRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.AssignNcrRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.ReinspectionDto;
import org.tornotron.echno_backend.modules.inspections.dtos.ReinspectionOutcomeRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.ScheduleReinspectionRequest;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventActorType;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventRecorder;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventService;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventSubjectType;
import org.tornotron.echno_backend.modules.inspections.mapper.ReinspectionMapperImpl;
import org.tornotron.echno_backend.modules.inspections.repositories.ReinspectionRepository;
import org.tornotron.echno_backend.modules.inspections.service.ReinspectionService;
import org.tornotron.echno_backend.modules.inspections.service.TradeService;
import org.tornotron.echno_backend.modules.inspections.mapper.TradeMapperImpl;
import org.tornotron.echno_backend.modules.inspections.domain.Inspection;
import org.tornotron.echno_backend.modules.inspections.dtos.CreateInspectionRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionDto;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionEventDto;
import org.tornotron.echno_backend.modules.inspections.mapper.ChecklistTemplateMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.DefectPhotoAnnotationMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.InspectionMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.NcrMapperImpl;
import org.tornotron.echno_backend.modules.inspections.repositories.InspectionRepository;
import org.tornotron.echno_backend.modules.inspections.service.ChecklistTemplateService;
import org.tornotron.echno_backend.modules.inspections.service.DefectAnnotationService;
import org.tornotron.echno_backend.modules.inspections.service.InspectionService;
import org.tornotron.echno_backend.modules.inspections.service.NcrService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
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
 * Reinspection against a real CockroachDB: scheduling from an NCR clones the failed check
 * points into a new inspection and numbers the attempt, a failed outcome rejects the NCR and
 * the next attempt takes the next number, a passed outcome is what the verify call may rest
 * on, the defect-only path verifies the defect, and none of it is visible to another tenant.
 *
 * <p>The annotations and the {@code @Import} list repeat {@link
 * org.tornotron.echno_backend.modules.inspections.InspectionServiceIT} to the letter so
 * Spring's context cache serves every inspection test class from one context. Keep them in
 * step when any one changes.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({InspectionService.class, InspectionMapperImpl.class,
        ChecklistTemplateService.class, ChecklistTemplateMapperImpl.class,
        NcrService.class, NcrMapperImpl.class,
        InspectionEventRecorder.class, InspectionEventService.class,
        TradeService.class, TradeMapperImpl.class,
        ReinspectionService.class, ReinspectionMapperImpl.class,
        DefectAnnotationService.class, DefectPhotoAnnotationMapperImpl.class,
        UserContextService.class,
        TenantEntityHelper.class, EntryNumberGenerator.class})
class ReinspectionServiceIT extends AbstractIntegrationTest {

    @Autowired
    private NcrService ncrService;

    @Autowired
    private ReinspectionService service;

    @Autowired
    private ReinspectionRepository reinspectionRepo;

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
            Organization orgA = persistOrganization("Reinsp Org A");
            Organization orgB = persistOrganization("Reinsp Org B");
            Project project = new Project();
            project.setProjectName("Tower R");
            project.setOrganization(orgA);
            entityManager.persist(project);
            persistUser("kc-rei-spare", "Spare");
            Employee qa = persistEmployee(orgA, "kc-rei-qa", "QA Lead E");
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
            deleteForOrgs("DELETE FROM inspection_reinspections WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM ncrs WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM inspection_defects WHERE inspection_id IN "
                    + "(SELECT id FROM inspections WHERE organization_id IN (:a,:b))");
            deleteForOrgs("DELETE FROM inspection_check_items WHERE inspection_id IN "
                    + "(SELECT id FROM inspections WHERE organization_id IN (:a,:b))");
            deleteForOrgs("DELETE FROM inspections WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM inspection_trades WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM document_sequence WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM project WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM employee WHERE organization_id IN (:a,:b)");
            entityManager.createNativeQuery(
                            "DELETE FROM users_table WHERE keycloak_id IN ('kc-rei-qa','kc-rei-spare')")
                    .executeUpdate();
            deleteForOrgs("DELETE FROM organization WHERE id IN (:a,:b)");
        });
    }

    @Test
    void scheduleForNcr_clonesTheFailedCheckPointsIntoANewScheduledInspection() {
        authenticateAs("kc-rei-qa");
        InspectionDto original = inspectionService.create(withChildren());
        NcrDto ncr = ncrAwaitingVerification(original.id());

        ReinspectionDto scheduled = service.scheduleForNcr(ncr.id(), new ScheduleReinspectionRequest(
                qaEmployeeId, LocalDate.of(2026, 9, 20), null));
        entityManager.flush();
        entityManager.clear();

        assertThat(scheduled.sequence()).isEqualTo(1);
        assertThat(scheduled.ncrId()).isEqualTo(ncr.id());
        assertThat(scheduled.originalInspectionId()).isEqualTo(original.id());
        assertThat(scheduled.outcome()).isEqualTo(ReinspectionOutcome.PENDING);
        assertThat(scheduled.requestedById()).isEqualTo(qaEmployeeId);
        assertThat(scheduled.projectId()).isEqualTo(projectId);

        InspectionDto recheck = inspectionService.findById(scheduled.reinspectionInspectionId());
        assertThat(recheck.title()).isEqualTo("Reinspection 1 of " + ncr.ncrNumber());
        assertThat(recheck.status()).isEqualTo(InspectionStatus.SCHEDULED);
        assertThat(recheck.type()).isEqualTo(original.type());
        assertThat(recheck.trade()).isEqualTo(original.trade());
        assertThat(recheck.projectId()).isEqualTo(projectId);
        assertThat(recheck.inspectorId()).isEqualTo(qaEmployeeId);
        assertThat(recheck.scheduledDate()).isEqualTo(LocalDate.of(2026, 9, 20));
        // only the failed check point came across, reset to pending; no defects are copied
        assertThat(recheck.checkItems()).hasSize(1);
        assertThat(recheck.checkItems().getFirst().checkPoint()).isEqualTo("Surface level");
        assertThat(recheck.checkItems().getFirst().status()).isEqualTo(CheckItemStatus.PENDING);
        assertThat(recheck.defects()).isEmpty();
        assertThat(recheck.inspectionNumber()).isNotEqualTo(original.inspectionNumber());

        assertThat(ncrTimeline(ncr.id())).extracting(InspectionEventDto::eventType)
                .contains("reinspection.scheduled");
        assertThat(timeline(original.id())).extracting(InspectionEventDto::eventType)
                .contains("reinspection.scheduled");
        assertThat(service.findByNcr(ncr.id())).hasSize(1);
    }

    @Test
    void scheduleForNcr_copiesEveryCheckPointOnRequest() {
        authenticateAs("kc-rei-qa");
        InspectionDto original = inspectionService.create(withChildren());
        NcrDto ncr = ncrAwaitingVerification(original.id());

        ReinspectionDto scheduled = service.scheduleForNcr(ncr.id(),
                new ScheduleReinspectionRequest(null, null, true));

        assertThat(inspectionService.findById(scheduled.reinspectionInspectionId()).checkItems()).hasSize(3);
    }

    @Test
    void scheduleForNcr_isRefusedUnlessTheNcrAwaitsVerification() {
        authenticateAs("kc-rei-qa");
        InspectionDto original = inspectionService.create(withChildren());
        NcrDto ncr = ncrService.create(new CreateNcrRequest(original.id(), null,
                "Cover below specification", "Measured 25 mm", DefectSeverity.MAJOR, qaEmployeeId, null));

        assertThatThrownBy(() -> service.scheduleForNcr(ncr.id(), new ScheduleReinspectionRequest(null, null, null)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("corrective action is reported complete");
        assertThat(reinspectionRepo.countByNcrIdAndOrganization_Id(ncr.id(), orgAId)).isZero();
    }

    @Test
    void scheduleForNcr_refusesASecondAttemptWhileOneIsPending() {
        authenticateAs("kc-rei-qa");
        InspectionDto original = inspectionService.create(withChildren());
        NcrDto ncr = ncrAwaitingVerification(original.id());
        service.scheduleForNcr(ncr.id(), new ScheduleReinspectionRequest(null, null, null));

        assertThatThrownBy(() -> service.scheduleForNcr(ncr.id(), new ScheduleReinspectionRequest(null, null, null)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("no outcome yet");
    }

    @Test
    void failedOutcome_rejectsTheNcrAndTheNextAttemptTakesTheNextNumber() {
        authenticateAs("kc-rei-qa");
        InspectionDto original = inspectionService.create(withChildren());
        NcrDto ncr = ncrAwaitingVerification(original.id());
        ReinspectionDto first = service.scheduleForNcr(ncr.id(), new ScheduleReinspectionRequest(null, null, null));

        ReinspectionDto failed = service.recordOutcome(first.id(),
                new ReinspectionOutcomeRequest(ReinspectionOutcome.FAILED, "Honeycombing still visible"));
        entityManager.flush();
        entityManager.clear();

        assertThat(failed.outcome()).isEqualTo(ReinspectionOutcome.FAILED);
        assertThat(failed.outcomeById()).isEqualTo(qaEmployeeId);
        assertThat(failed.outcomeAt()).isNotNull();
        NcrDto rejected = ncrService.findById(ncr.id());
        assertThat(rejected.status()).isEqualTo(NcrStatus.REJECTED);
        assertThat(rejected.verificationRemarks()).isEqualTo("Honeycombing still visible");

        // back round the loop: reassign, report complete, second attempt
        ncrService.assign(ncr.id(), new AssignNcrRequest(qaEmployeeId, null));
        ncrService.markCorrectiveActionComplete(ncr.id(), "Grouted");
        ReinspectionDto second = service.scheduleForNcr(ncr.id(), new ScheduleReinspectionRequest(null, null, null));
        assertThat(second.sequence()).isEqualTo(2);
        assertThat(inspectionService.findById(second.reinspectionInspectionId()).title())
                .isEqualTo("Reinspection 2 of " + ncr.ncrNumber());

        assertThatThrownBy(() -> service.recordOutcome(first.id(),
                new ReinspectionOutcomeRequest(ReinspectionOutcome.PASSED, null)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("already has the outcome");

        entityManager.flush();
        entityManager.clear();
        assertThat(ncrTimeline(ncr.id())).extracting(InspectionEventDto::eventType).containsSubsequence(
                "reinspection.scheduled", "reinspection.outcome.recorded", "ncr.rejected",
                "ncr.assigned", "ncr.corrective_action.complete", "reinspection.scheduled");
    }

    @Test
    void passedOutcome_letsVerifyRestOnTheReinspection() {
        authenticateAs("kc-rei-qa");
        InspectionDto original = inspectionService.create(withChildren());
        NcrDto ncr = ncrAwaitingVerification(original.id());
        ReinspectionDto attempt = service.scheduleForNcr(ncr.id(), new ScheduleReinspectionRequest(null, null, null));

        // a pending reinspection verifies nothing
        assertThatThrownBy(() -> ncrService.verify(ncr.id(), "Accepted", attempt.id()))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("only a passed reinspection");

        ReinspectionDto passed = service.recordOutcome(attempt.id(),
                new ReinspectionOutcomeRequest(ReinspectionOutcome.PASSED, "Cover re-measured at 42 mm"));
        // the outcome alone does not move the NCR: verification is the role-gated step
        assertThat(ncrService.findById(ncr.id()).status()).isEqualTo(NcrStatus.CORRECTIVE_ACTION_COMPLETE);

        NcrDto verified = ncrService.verify(ncr.id(), "Accepted", attempt.id());
        entityManager.flush();
        entityManager.clear();

        assertThat(verified.status()).isEqualTo(NcrStatus.VERIFIED);
        assertThat(verified.verifiedById()).isEqualTo(passed.outcomeById());
        assertThat(verified.verifiedAt()).isEqualTo(passed.outcomeAt());
        List<InspectionEventDto> events = ncrTimeline(ncr.id());
        assertThat(events).extracting(InspectionEventDto::eventType).contains("ncr.verified")
                .doesNotContain("ncr.verified.without_reinspection");
        InspectionEventDto verify = events.stream().filter(e -> e.eventType().equals("ncr.verified")).findFirst().orElseThrow();
        assertThat(verify.after()).containsEntry("reinspectionId", attempt.id().toString())
                .containsEntry("status", "verified");
    }

    @Test
    void verify_refusesAReinspectionOfAnotherNcr() {
        authenticateAs("kc-rei-qa");
        InspectionDto original = inspectionService.create(withChildren());
        NcrDto one = ncrAwaitingVerification(original.id());
        NcrDto other = ncrAwaitingVerification(original.id());
        ReinspectionDto attempt = service.scheduleForNcr(other.id(), new ScheduleReinspectionRequest(null, null, null));
        service.recordOutcome(attempt.id(), new ReinspectionOutcomeRequest(ReinspectionOutcome.PASSED, null));

        assertThatThrownBy(() -> ncrService.verify(one.id(), "Accepted", attempt.id()))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("does not belong to NCR");
        assertThat(ncrService.findById(one.id()).status()).isEqualTo(NcrStatus.CORRECTIVE_ACTION_COMPLETE);
    }

    @Test
    void verify_withoutAReinspection_isStillAllowedAndLoggedAsSuch() {
        authenticateAs("kc-rei-qa");
        InspectionDto original = inspectionService.create(withChildren());
        NcrDto ncr = ncrAwaitingVerification(original.id());

        assertThat(ncrService.verify(ncr.id(), "Accepted on site", null).status()).isEqualTo(NcrStatus.VERIFIED);
        entityManager.flush();
        entityManager.clear();
        assertThat(ncrTimeline(ncr.id())).extracting(InspectionEventDto::eventType)
                .contains("ncr.verified.without_reinspection");
    }

    @Test
    void defectOnlyPath_verifiesTheDefectOnPassAndReopensItOnFail() {
        authenticateAs("kc-rei-qa");
        InspectionDto original = inspectionService.create(withChildren());
        UUID defectId = markDefectResolved(original.id());

        ReinspectionDto attempt = service.scheduleForDefect(defectId, new ScheduleReinspectionRequest(null, null, null));
        assertThat(attempt.defectId()).isEqualTo(defectId);
        assertThat(attempt.ncrId()).isNull();
        assertThat(attempt.sequence()).isEqualTo(1);

        service.recordOutcome(attempt.id(), new ReinspectionOutcomeRequest(ReinspectionOutcome.FAILED, "Still uneven"));
        entityManager.flush();
        entityManager.clear();
        assertThat(inspectionService.findById(original.id()).defects().getFirst().status())
                .isEqualTo(DefectStatus.IN_PROGRESS);

        UUID again = markDefectResolved(original.id());
        ReinspectionDto second = service.scheduleForDefect(again, new ScheduleReinspectionRequest(null, null, null));
        service.recordOutcome(second.id(), new ReinspectionOutcomeRequest(ReinspectionOutcome.PASSED, "Level now"));
        entityManager.flush();
        entityManager.clear();
        assertThat(inspectionService.findById(original.id()).defects().getFirst().status())
                .isEqualTo(DefectStatus.VERIFIED);
        assertThat(timeline(original.id())).extracting(InspectionEventDto::eventType)
                .contains("reinspection.outcome.recorded", "defect.status.changed");
    }

    @Test
    void scheduleForDefect_isRefusedUnlessTheDefectIsResolved() {
        authenticateAs("kc-rei-qa");
        InspectionDto original = inspectionService.create(withChildren());
        UUID defectId = original.defects().getFirst().id();

        assertThatThrownBy(() -> service.scheduleForDefect(defectId, new ScheduleReinspectionRequest(null, null, null)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("reported resolved");
    }

    @Test
    void reinspections_areInvisibleToAnotherTenant() {
        authenticateAs("kc-rei-qa");
        InspectionDto original = inspectionService.create(withChildren());
        NcrDto ncr = ncrAwaitingVerification(original.id());
        ReinspectionDto attempt = service.scheduleForNcr(ncr.id(), new ScheduleReinspectionRequest(null, null, null));
        entityManager.flush();
        entityManager.clear();

        TenantContext.setCurrentOrgId(orgBId);
        assertThatThrownBy(() -> service.findById(attempt.id())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.findByNcr(ncr.id())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.recordOutcome(attempt.id(),
                new ReinspectionOutcomeRequest(ReinspectionOutcome.PASSED, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.scheduleForDefect(original.defects().getFirst().id(),
                new ScheduleReinspectionRequest(null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);

        TenantContext.setCurrentOrgId(orgAId);
        assertThat(service.findById(attempt.id()).outcome()).isEqualTo(ReinspectionOutcome.PENDING);
    }

    private NcrDto ncrAwaitingVerification(UUID inspectionId) {
        NcrDto ncr = ncrService.create(new CreateNcrRequest(inspectionId, null,
                "Cover below specification", "Measured 25 mm", DefectSeverity.MAJOR, qaEmployeeId,
                LocalDate.of(2026, 9, 30)));
        ncrService.markCorrectiveActionComplete(ncr.id(), "Re-poured");
        return ncrService.findById(ncr.id());
    }

    /** Re-sends the inspection with its one defect reported resolved, and returns that defect's new id. */
    private UUID markDefectResolved(UUID inspectionId) {
        InspectionDto current = inspectionService.findById(inspectionId);
        InspectionDto updated = inspectionService.update(inspectionId, new UpdateInspectionRequest(
                current.title(), current.type(), current.category(), current.trade(), null,
                current.status(), current.result(), projectId,
                current.location(), null, null, current.scheduledDate(), null,
                null, null, null, current.inspectorId(), null, null, null, null, null,
                current.checkItems().stream().map(c -> new InspectionCheckItemRequest(c.category(), c.checkPoint(),
                        c.specification(), c.status(), c.remarks(), c.photosRequired(), c.photos(),
                        c.measurement(), c.expectedValue(), c.acceptanceCriterion(), c.tolerance(),
                        c.bimElementGuid(), c.priority())).toList(),
                List.of(new InspectionDefectRequest("Finishing", "Uneven surface near grid B2",
                        DefectSeverity.MINOR, "Grid B2", null, "Re-level and re-finish",
                        "Contractor", LocalDate.of(2026, 9, 20), DefectStatus.RESOLVED, LocalDate.of(2026, 9, 15)))));
        entityManager.flush();
        return updated.defects().getFirst().id();
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
                                null, null, null, null, null, "high"),
                        new InspectionCheckItemRequest("Structural", "Rebar spacing",
                                null, CheckItemStatus.PENDING, null, false, null,
                                null, null, null, null, null, "medium"),
                        new InspectionCheckItemRequest("Finishing", "Surface level",
                                null, CheckItemStatus.FAILED, null, false, null,
                                null, null, null, null, null, "low")),
                List.of(new InspectionDefectRequest("Finishing", "Uneven surface near grid B2",
                        DefectSeverity.MINOR, "Grid B2", null, "Re-level and re-finish",
                        "Contractor", LocalDate.of(2026, 9, 20), null, null)));
    }

    private UpdateInspectionRequest headerOnly(InspectionStatus status, InspectionResult result) {
        return new UpdateInspectionRequest(
                "Slab check", InspectionType.QUALITY, null, null, null, status, result, projectId,
                "Block A", null, null, LocalDate.of(2026, 9, 12), null,
                null, null, null, 100L, null, null, null, null, null,
                List.of(), List.of());
    }

    private Inspection scheduledInspection() {
        InspectionDto dto = inspectionService.create(new CreateInspectionRequest(
                "Slab check", InspectionType.QUALITY, null, null, null, projectId,
                "Block A", null, null, LocalDate.of(2026, 9, 12), null,
                null, null, null, 100L, null, null, null, null, null,
                null, null));
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
