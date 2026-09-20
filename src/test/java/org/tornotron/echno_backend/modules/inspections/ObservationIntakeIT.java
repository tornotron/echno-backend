package org.tornotron.echno_backend.modules.inspections;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.HibernateFilterConfig;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.multitenancy.TenantIsolationListenerRegistrar;
import org.tornotron.echno_backend.common.multitenancy.UnscopedAccessGuard;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.retry.TransactionalWorkRunner;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionEvent;
import org.tornotron.echno_backend.modules.inspections.domain.Observation;
import org.tornotron.echno_backend.modules.inspections.dtos.IntakeObservationRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.ObservationDto;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventRecorder;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventService;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventType;
import org.tornotron.echno_backend.modules.inspections.mapper.ChecklistTemplateMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.DefectPhotoAnnotationMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.ElementTypeMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.InspectionMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.NcrMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.ObservationMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.TradeMapperImpl;
import org.tornotron.echno_backend.modules.inspections.repositories.ObservationRepository;
import org.tornotron.echno_backend.modules.inspections.service.ChecklistTemplateService;
import org.tornotron.echno_backend.modules.inspections.service.DefectAnnotationService;
import org.tornotron.echno_backend.modules.inspections.service.ElementTypeService;
import org.tornotron.echno_backend.modules.inspections.service.InspectionService;
import org.tornotron.echno_backend.modules.inspections.service.NcrService;
import org.tornotron.echno_backend.modules.inspections.service.ObservationService;
import org.tornotron.echno_backend.modules.inspections.service.TradeService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.spatial.SpatialLevel;
import org.tornotron.echno_backend.project.spatial.SpatialNodeService;
import org.tornotron.echno_backend.project.spatial.dto.CreateSpatialNodeRequest;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

/**
 * Machine intake against a real database: a device's finding lands pending with its metadata,
 * a repeat on the producer's reference returns the same row, a repeat that races the first
 * past the check is answered with the first's row rather than a constraint failure, the human
 * source is refused, the spatial node is checked against the project, and the reference is
 * unique per organisation rather than globally.
 *
 * <p>Unlike the other inspection slices this one runs with the application's own transaction
 * and tenant wiring: {@link HibernateFilterConfig} enabling the {@code orgFilter} on every
 * {@code @Transactional} boundary, the fail-closed load listener behind it, and no test-managed
 * transaction. Intake is not one transaction (a lost race on {@code externalRef} restarts in a
 * fresh one, #814), and a slice that enables the filter by hand on one session and rolls
 * everything back at the end cannot see either of those: the restarted transaction would run
 * unfiltered, which is the cross-tenant read the first attempt at this reported. Rows are
 * committed and removed after each test instead.
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
        TenantEntityHelper.class, EntryNumberGenerator.class,
        TransactionalWorkRunner.class, TransactionRetryTemplate.class, SimpleMeterRegistry.class,
        AopAutoConfiguration.class, HibernateFilterConfig.class,
        TenantIsolationListenerRegistrar.class, UnscopedAccessGuard.class})
@TestPropertySource(properties = {
        "echno.transaction.retry.initial-backoff-millis=0",
        "echno.transaction.retry.max-backoff-millis=0"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ObservationIntakeIT extends AbstractIntegrationTest {

    @Autowired
    private ObservationService service;

    @Autowired
    private SpatialNodeService spatial;

    /** Spied so a test can slip a competing commit in between the check and the insert. */
    @MockitoSpyBean
    private ObservationRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private Long orgAId;
    private Long orgBId;
    private Long projectId;
    private Long otherProjectId;
    private UUID zone;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        TenantContext.declareUnscoped("ObservationIntakeIT seed");
        inCommittedTx(() -> {
            Organization orgA = persistOrganization("Org A");
            Organization orgB = persistOrganization("Org B");
            Project project = new Project();
            project.setProjectName("Tower A");
            project.setOrganization(orgA);
            entityManager.persist(project);
            Project other = new Project();
            other.setProjectName("Tower B");
            other.setOrganization(orgB);
            entityManager.persist(other);
            entityManager.flush();
            orgAId = orgA.getId();
            orgBId = orgB.getId();
            projectId = project.getId();
            otherProjectId = other.getId();
        });
        TenantContext.clear();
        TenantContext.setCurrentOrgId(orgAId);
        UUID building = node(null, SpatialLevel.BUILDING, "B1");
        UUID floor = node(building, SpatialLevel.FLOOR, "L01");
        zone = node(floor, SpatialLevel.ZONE, "Z1");
    }

    @AfterEach
    void removeCommittedRows() {
        TenantContext.clear();
        if (orgAId == null && orgBId == null) {
            return;
        }
        TenantContext.setBypass(true);
        try {
            inCommittedTx(() -> {
                deleteForOrgs("DELETE FROM inspection_events WHERE organization_id IN (:a,:b)");
                deleteForOrgs("DELETE FROM ncrs WHERE organization_id IN (:a,:b)");
                deleteForOrgs("DELETE FROM inspection_defects WHERE inspection_id IN "
                        + "(SELECT id FROM inspections WHERE organization_id IN (:a,:b))");
                deleteForOrgs("DELETE FROM inspection_check_items WHERE inspection_id IN "
                        + "(SELECT id FROM inspections WHERE organization_id IN (:a,:b))");
                deleteForOrgs("DELETE FROM inspection_observations WHERE organization_id IN (:a,:b)");
                deleteForOrgs("DELETE FROM inspections WHERE organization_id IN (:a,:b)");
                deleteForOrgs("DELETE FROM inspection_trades WHERE organization_id IN (:a,:b)");
                deleteForOrgs("DELETE FROM org_element_types WHERE organization_id IN (:a,:b)");
                deleteForOrgs("DELETE FROM project_spatial_node WHERE organization_id IN (:a,:b)");
                deleteForOrgs("DELETE FROM document_sequence WHERE organization_id IN (:a,:b)");
                deleteForOrgs("DELETE FROM project WHERE organization_id IN (:a,:b)");
                deleteForOrgs("DELETE FROM organization WHERE id IN (:a,:b)");
            });
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void aDeviceFindingLandsPendingWithItsMetadataAndIsIdempotentOnTheReference() {
        IntakeObservationRequest req = new IntakeObservationRequest(projectId, "drone-7:mission-3:42",
                ObservationSource.DRONE, "drone-7", "mission-3", "cap-9f", zone, null,
                LocalDateTime.of(2026, 9, 12, 8, 15), "Exposed rebar", "Rebar visible at slab edge",
                "Structural", DefectSeverity.MAJOR, "crackdet", "1.4.0", new BigDecimal("0.8125"),
                List.of(Map.of("frame", 1200, "bbox", List.of(10, 20, 110, 90))));

        ObservationService.IntakeResult first = service.intake(req);
        assertThat(first.created()).isTrue();
        ObservationDto dto = first.observation();
        assertThat(dto.reviewStatus()).isEqualTo(ObservationReviewStatus.PENDING);
        assertThat(dto.source()).isEqualTo(ObservationSource.DRONE);
        assertThat(dto.sourceDeviceId()).isEqualTo("drone-7");
        assertThat(dto.missionRef()).isEqualTo("mission-3");
        assertThat(dto.captureRef()).isEqualTo("cap-9f");
        assertThat(dto.externalRef()).isEqualTo("drone-7:mission-3:42");
        assertThat(dto.modelName()).isEqualTo("crackdet");
        assertThat(dto.modelVersion()).isEqualTo("1.4.0");
        assertThat(dto.confidence()).isEqualByComparingTo("0.8125");
        assertThat(dto.inspectionId()).isNull();
        assertThat(dto.reviewedAt()).isNull();
        assertThat(dto.outcomeKind()).isEqualTo(ObservationOutcomeKind.NONE);
        assertThat(dto.spatialPath()).hasSize(3);
        assertThat(dto.evidenceRefs()).hasSize(1);
        assertThat(events(dto.id(), InspectionEventType.OBSERVATION_CREATED)).hasSize(1)
                .allSatisfy(e -> assertThat(e.getActorId()).isEqualTo("drone-7"));

        // the retried upload: same row back, nothing new written
        ObservationService.IntakeResult again = service.intake(new IntakeObservationRequest(projectId,
                "drone-7:mission-3:42", ObservationSource.DRONE, "drone-7", "mission-3", "cap-9f", zone, null,
                LocalDateTime.of(2026, 9, 12, 8, 16), "Exposed rebar, retry", null, null, null, null, null,
                null, null));
        assertThat(again.created()).isFalse();
        assertThat(again.observation().id()).isEqualTo(dto.id());
        assertThat(again.observation().title()).isEqualTo("Exposed rebar");
        assertThat(service.findAll(projectId, ObservationReviewStatus.PENDING, null, null, null, null, null,
                PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
        assertThat(events(dto.id(), InspectionEventType.OBSERVATION_CREATED)).hasSize(1);
    }

    /**
     * The race #814 item 2 describes. Both retries of one capture pass the check before either
     * inserts; the second insert is refused by {@code uk_insp_obs_external_ref}. The loser is
     * answered with the winner's row (200), the same as a repeat that arrived late enough to be
     * caught by the check, and nothing of the loser's attempt survives: one row, no created
     * event from the loser, the winner's title.
     *
     * <p>The competing commit is placed deterministically: the repository spy runs the first
     * check for real, commits the winner's row in a transaction of its own, and only then hands
     * the empty answer back, so the insert that follows always collides. What the database
     * reports for the collision depends on what the losing transaction had read. CockroachDB
     * answers this shape with a serialization abort (SQLSTATE 40001), because the check's read
     * of the index span is invalidated by the winner's commit; the sibling test below covers the
     * unique violation itself. Both restart the transaction, and the second check finds the row.
     */
    @Test
    void aRepeatThatRacesTheFirstPastTheCheckIsAnsweredWithTheFirstsRow() {
        AtomicReference<UUID> winner = raceTheFirstCheck(true);

        ObservationService.IntakeResult loser = service.intake(retryOf("drone-7:mission-3:42"));

        assertLoserGotTheWinnersRow(loser, winner.get());
    }

    /**
     * The same race where the loser's transaction has not read the index span before the
     * insert (the first check is answered empty without a read), so nothing is invalidated
     * and the insert is refused by the index itself: SQLSTATE 23505, the
     * {@code DataIntegrityViolationException} the issue names. That is the failure the retry
     * predicate on intake exists for; without it this would be a 409 with the constraint's name.
     */
    @Test
    void aRepeatRefusedByTheUniqueIndexIsAnsweredWithTheFirstsRow() {
        AtomicReference<UUID> winner = raceTheFirstCheck(false);

        ObservationService.IntakeResult loser = service.intake(retryOf("drone-7:mission-3:42"));

        assertLoserGotTheWinnersRow(loser, winner.get());
    }

    @Test
    void anAiFindingNamesTheModelAsActorAndTheHumanSourceIsRefused() {
        ObservationService.IntakeResult ai = service.intake(new IntakeObservationRequest(projectId, "cv:77",
                ObservationSource.AI, "cctv-gate-2", null, null, null, "Gate 2", LocalDateTime.of(2026, 9, 12, 9, 0),
                "No helmet", null, "Safety", null, "ppe-detect", "2.0", new BigDecimal("0.55"), null));
        assertThat(events(ai.observation().id(), InspectionEventType.OBSERVATION_CREATED))
                .allSatisfy(e -> assertThat(e.getActorId()).isEqualTo("ppe-detect"));

        assertThatThrownBy(() -> service.intake(new IntakeObservationRequest(projectId, "h:1",
                ObservationSource.HUMAN, "person", null, null, null, null, LocalDateTime.of(2026, 9, 12, 9, 0),
                "Seen", null, null, null, null, null, null, null)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void aNodeOutsideTheProjectIsRefused() {
        assertThatThrownBy(() -> service.intake(new IntakeObservationRequest(projectId, "drone-7:9",
                ObservationSource.DRONE, "drone-7", null, null, UUID.randomUUID(), null,
                LocalDateTime.of(2026, 9, 12, 9, 0), "Crack", null, null, null, null, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theReferenceIsUniquePerOrganisationNotGlobally() {
        ObservationDto mine = service.intake(new IntakeObservationRequest(projectId, "shared-ref",
                ObservationSource.ROBOT, "spot-1", null, null, null, null, LocalDateTime.of(2026, 9, 12, 9, 0),
                "Mine", null, null, null, null, null, null, null)).observation();

        TenantContext.setCurrentOrgId(orgBId);
        // the other tenant cannot see it, and its own use of the same reference is a new row
        assertThatThrownBy(() -> service.findById(mine.id())).isInstanceOf(ResourceNotFoundException.class);
        // a project of the other tenant is not theirs to post against
        assertThatThrownBy(() -> service.intake(new IntakeObservationRequest(projectId, "shared-ref",
                ObservationSource.ROBOT, "spot-1", null, null, null, null, LocalDateTime.of(2026, 9, 12, 9, 0),
                "Theirs", null, null, null, null, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        ObservationService.IntakeResult theirs = service.intake(new IntakeObservationRequest(otherProjectId,
                "shared-ref", ObservationSource.ROBOT, "spot-1", null, null, null, null,
                LocalDateTime.of(2026, 9, 12, 9, 0), "Theirs", null, null, null, null, null, null, null));
        assertThat(theirs.created()).isTrue();
        assertThat(theirs.observation().id()).isNotEqualTo(mine.id());
        assertThat(repository.existsByIdAndOrganization_Id(theirs.observation().id(), orgBId)).isTrue();
    }

    // ---------------------------------------------------------- helpers

    /**
     * Arranges the first {@code findByExternalRefScoped} call to be overtaken: the winner's row
     * is committed in a transaction of its own before the empty answer is returned, so the
     * insert that follows collides. With {@code readFirst} the check runs for real before the
     * competing commit; without it the empty answer is given straight away and the losing
     * transaction never reads the span. Every later call is answered by the repository.
     *
     * @return where the winner's id lands once the race has been run
     */
    private AtomicReference<UUID> raceTheFirstCheck(boolean readFirst) {
        // The repository bean is a JDK proxy, so the spy answers by delegating to it rather than
        // by calling a real method; that delegate is the spy's default answer.
        Answer<?> delegate = mockingDetails(repository).getMockCreationSettings().getDefaultAnswer();
        AtomicInteger checks = new AtomicInteger();
        AtomicReference<UUID> winner = new AtomicReference<>();
        doAnswer(invocation -> {
            if (checks.incrementAndGet() > 1) {
                return delegate.answer(invocation);
            }
            if (readFirst) {
                assertThat((Optional<?>) delegate.answer(invocation)).isEmpty();
            }
            winner.set(commitCompetingRow("drone-7:mission-3:42", "Exposed rebar"));
            return Optional.empty();
        }).when(repository).findByExternalRefScoped(anyString());
        return winner;
    }

    private IntakeObservationRequest retryOf(String externalRef) {
        return new IntakeObservationRequest(projectId, externalRef, ObservationSource.DRONE, "drone-7",
                "mission-3", "cap-9f", zone, null, LocalDateTime.of(2026, 9, 12, 8, 16), "Exposed rebar, retry",
                null, null, null, null, null, null, null);
    }

    private void assertLoserGotTheWinnersRow(ObservationService.IntakeResult loser, UUID winner) {
        assertThat(winner).isNotNull();
        assertThat(loser.created()).isFalse();
        assertThat(loser.observation().id()).isEqualTo(winner);
        assertThat(loser.observation().title()).isEqualTo("Exposed rebar");
        verify(repository, times(2)).findByExternalRefScoped("drone-7:mission-3:42");
        assertThat(service.findAll(projectId, ObservationReviewStatus.PENDING, null, null, null, null, null,
                PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
        // nothing of the loser's attempt survived: the bare winner has no event, and the
        // rolled-back attempt left none
        assertThat(entityManager.createQuery(
                        "SELECT COUNT(e) FROM InspectionEvent e WHERE e.eventType = :type AND e.organization.id = :org",
                        Long.class)
                .setParameter("type", InspectionEventType.OBSERVATION_CREATED)
                .setParameter("org", orgAId)
                .getSingleResult()).isZero();
    }

    /** The winner of the race: a bare row on the reference, committed in its own transaction. */
    private UUID commitCompetingRow(String externalRef, String title) {
        Observation o = new Observation();
        o.setOrganization(entityManager.getReference(Organization.class, orgAId));
        o.setProjectId(projectId);
        o.setSource(ObservationSource.DRONE);
        o.setSourceDeviceId("drone-7");
        o.setExternalRef(externalRef);
        o.setObservedAt(LocalDateTime.of(2026, 9, 12, 8, 15));
        o.setTitle(title);
        o.setReviewStatus(ObservationReviewStatus.PENDING);
        o.setOutcomeKind(ObservationOutcomeKind.NONE);
        inCommittedTx(() -> entityManager.persist(o));
        return o.getId();
    }

    private List<InspectionEvent> events(UUID subjectId, String type) {
        return entityManager.createQuery(
                        "SELECT e FROM InspectionEvent e WHERE e.subjectId = :id AND e.eventType = :type",
                        InspectionEvent.class)
                .setParameter("id", subjectId)
                .setParameter("type", type)
                .getResultList();
    }

    private UUID node(UUID parent, SpatialLevel level, String code) {
        return spatial.create(projectId, new CreateSpatialNodeRequest(parent, level, code, code,
                null, null, null, null, null)).id();
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
