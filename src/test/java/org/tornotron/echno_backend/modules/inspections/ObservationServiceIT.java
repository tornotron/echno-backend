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
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.modules.inspections.domain.Inspection;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionEvent;
import org.tornotron.echno_backend.modules.inspections.domain.Observation;
import org.tornotron.echno_backend.modules.inspections.dtos.CreateInspectionRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.CreateNcrRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.CreateObservationRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionCheckItemRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionDefectRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionDto;
import org.tornotron.echno_backend.modules.inspections.dtos.NcrDto;
import org.tornotron.echno_backend.modules.inspections.dtos.ObservationDto;
import org.tornotron.echno_backend.modules.inspections.dtos.ReviewObservationRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.UpdateInspectionRequest;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventRecorder;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventService;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventType;
import org.tornotron.echno_backend.modules.inspections.mapper.ChecklistTemplateMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.DefectPhotoAnnotationMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.InspectionMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.NcrMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.ObservationMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.TradeMapperImpl;
import org.tornotron.echno_backend.modules.inspections.observation.ObservationAlreadyReviewedException;
import org.tornotron.echno_backend.modules.inspections.repositories.ObservationRepository;
import org.tornotron.echno_backend.modules.inspections.service.ChecklistTemplateService;
import org.tornotron.echno_backend.modules.inspections.service.DefectAnnotationService;
import org.tornotron.echno_backend.modules.inspections.service.InspectionService;
import org.tornotron.echno_backend.modules.inspections.service.NcrService;
import org.tornotron.echno_backend.modules.inspections.service.ObservationService;
import org.tornotron.echno_backend.modules.inspections.service.TradeService;
import org.tornotron.echno_backend.modules.inspections.service.ElementTypeService;
import org.tornotron.echno_backend.modules.inspections.mapper.ElementTypeMapperImpl;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.spatial.SpatialLevel;
import org.tornotron.echno_backend.project.spatial.SpatialNodeService;
import org.tornotron.echno_backend.project.spatial.dto.CreateSpatialNodeRequest;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The observation life cycle against a real database: the review transitions and the one
 * decision rule, the outcome linkage, the implicit observation every failed item, defect
 * and NCR gets through the existing forms, the carry-over across an inspection update, and
 * tenant isolation.
 *
 * <p>Same annotations and {@code @Import} list as {@link InspectionServiceIT}, to the letter,
 * so the context cache hands both one Spring context.
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
class ObservationServiceIT extends AbstractIntegrationTest {

    @Autowired
    private ObservationService service;

    @Autowired
    private InspectionService inspections;

    @Autowired
    private NcrService ncrs;

    @Autowired
    private SpatialNodeService spatial;

    @Autowired
    private ObservationRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private Long orgAId;
    private Long orgBId;
    private Long projectId;
    private UUID zone;

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
        enableOrgFilter(orgAId);
        UUID building = node(null, SpatialLevel.BUILDING, "B1");
        UUID floor = node(building, SpatialLevel.FLOOR, "L01");
        zone = node(floor, SpatialLevel.ZONE, "Z1");
    }

    @AfterEach
    void clearTenantState() {
        disableOrgFilter();
        TenantContext.clear();
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
            deleteForOrgs("DELETE FROM inspection_trades WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM org_element_types WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM project_spatial_node WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM document_sequence WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM project WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM organization WHERE id IN (:a,:b)");
        });
    }

    // ------------------------------------------------------------ human

    @Test
    void humanObservationIsAcceptedOnCreationWithItsNodeAndEvidence() {
        ObservationDto dto = service.create(new CreateObservationRequest(projectId, null, zone,
                "Column C-14", null, "Hairline crack", "Crack at column C-14, hairline", "Structural",
                DefectSeverity.MINOR, List.of(41L, 42L)));

        assertThat(dto.source()).isEqualTo(ObservationSource.HUMAN);
        assertThat(dto.reviewStatus()).isEqualTo(ObservationReviewStatus.ACCEPTED);
        assertThat(dto.outcomeKind()).isEqualTo(ObservationOutcomeKind.NONE);
        assertThat(dto.reviewedAt()).isNotNull();
        assertThat(dto.spatialPath()).hasSize(3);
        assertThat(dto.evidenceRefs()).extracting(m -> m.get("attachmentId")).containsExactly(41L, 42L);
        assertThat(events(dto.id(), InspectionEventType.OBSERVATION_CREATED)).hasSize(1);
        assertThat(service.findAll(projectId, null, ObservationSource.HUMAN, null, zone, null, null,
                PageRequest.of(0, 10)).getContent()).extracting(ObservationDto::id).containsExactly(dto.id());
    }

    // ----------------------------------------------------------- review

    @Test
    void pendingToAcceptedLinksACheckItemResultOnce() {
        InspectionDto inspection = inspections.create(request(
                List.of(checkItem("Rebar cover", CheckItemStatus.PENDING)), List.of()));
        UUID itemId = inspection.checkItems().get(0).id();
        Observation pending = pending(inspection.id(), "Cover below spec");

        ObservationDto reviewed = service.review(pending.getId(), new ReviewObservationRequest(
                ObservationReviewDecision.ACCEPT, null, null,
                new ReviewObservationRequest.Outcome(ObservationOutcomeKind.CHECK_ITEM, itemId,
                        CheckItemStatus.FAILED, null, null, null)));

        assertThat(reviewed.reviewStatus()).isEqualTo(ObservationReviewStatus.ACCEPTED);
        assertThat(reviewed.outcomeKind()).isEqualTo(ObservationOutcomeKind.CHECK_ITEM);
        assertThat(reviewed.outcomeRef()).isEqualTo(itemId);
        assertThat(reviewed.reviewChanges()).isNull();
        entityManager.flush();
        entityManager.clear();
        InspectionDto after = inspections.findById(inspection.id());
        assertThat(after.checkItems().get(0).status()).isEqualTo(CheckItemStatus.FAILED);
        assertThat(after.failedCheckPoints()).isEqualTo(1);
        assertThat(events(pending.getId(), InspectionEventType.OBSERVATION_REVIEWED)).hasSize(1);

        // one decision per observation: the second is refused and changes nothing
        assertThatThrownBy(() -> service.review(pending.getId(), new ReviewObservationRequest(
                ObservationReviewDecision.REJECT, "changed my mind", null, null)))
                .isInstanceOf(ObservationAlreadyReviewedException.class);
        assertThat(service.findById(pending.getId()).reviewStatus()).isEqualTo(ObservationReviewStatus.ACCEPTED);
    }

    @Test
    void pendingToModifiedStoresTheDiffAndCreatesExactlyOneDefect() {
        InspectionDto inspection = inspections.create(request(List.of(), List.of()));
        Observation pending = pending(inspection.id(), "Honeycombing");
        pending.setSuggestedSeverity(DefectSeverity.MINOR);
        entityManager.flush();

        // a modify with nothing that differs from the proposal is refused
        assertThatThrownBy(() -> service.review(pending.getId(), new ReviewObservationRequest(
                ObservationReviewDecision.MODIFY, null,
                new ReviewObservationRequest.Changes("Honeycombing", null, DefectSeverity.MINOR, null, null),
                ReviewObservationRequest.Outcome.none())))
                .isInstanceOf(InvalidRequestException.class);

        ObservationDto reviewed = service.review(pending.getId(), new ReviewObservationRequest(
                ObservationReviewDecision.MODIFY, "worse than it looked",
                new ReviewObservationRequest.Changes(null, null, DefectSeverity.MAJOR, zone, null),
                new ReviewObservationRequest.Outcome(ObservationOutcomeKind.DEFECT, null, null, null,
                        defect("Honeycombing on column C4"), null)));

        assertThat(reviewed.reviewStatus()).isEqualTo(ObservationReviewStatus.MODIFIED);
        assertThat(reviewed.reviewNote()).isEqualTo("worse than it looked");
        assertThat(reviewed.reviewChanges()).extracting(m -> m.get("field")).containsExactly("severity", "spatialNodeId");
        assertThat(reviewed.reviewChanges().get(0)).containsEntry("before", "minor").containsEntry("after", "major");
        // the proposal is not rewritten
        assertThat(reviewed.suggestedSeverity()).isEqualTo(DefectSeverity.MINOR);
        assertThat(reviewed.outcomeKind()).isEqualTo(ObservationOutcomeKind.DEFECT);

        entityManager.flush();
        entityManager.clear();
        InspectionDto after = inspections.findById(inspection.id());
        assertThat(after.defects()).hasSize(1);
        assertThat(after.defects().get(0).id()).isEqualTo(reviewed.outcomeRef());
        assertThat(after.defects().get(0).observationId()).isEqualTo(pending.getId());
        assertThat(after.defectsFound()).isEqualTo(1);
    }

    @Test
    void pendingToRejectedNeedsANoteAndLinksNothing() {
        InspectionDto inspection = inspections.create(request(
                List.of(checkItem("Rebar cover", CheckItemStatus.PENDING)), List.of()));
        Observation pending = pending(inspection.id(), "False positive");

        assertThatThrownBy(() -> service.review(pending.getId(), new ReviewObservationRequest(
                ObservationReviewDecision.REJECT, " ", null, null)))
                .isInstanceOf(InvalidRequestException.class);

        ObservationDto rejected = service.review(pending.getId(), new ReviewObservationRequest(
                ObservationReviewDecision.REJECT, "shadow, not a crack", null,
                new ReviewObservationRequest.Outcome(ObservationOutcomeKind.CHECK_ITEM,
                        inspection.checkItems().get(0).id(), CheckItemStatus.FAILED, null, null, null)));

        assertThat(rejected.reviewStatus()).isEqualTo(ObservationReviewStatus.REJECTED);
        assertThat(rejected.outcomeKind()).isEqualTo(ObservationOutcomeKind.NONE);
        assertThat(rejected.outcomeRef()).isNull();
        entityManager.flush();
        entityManager.clear();
        assertThat(inspections.findById(inspection.id()).checkItems().get(0).status())
                .isEqualTo(CheckItemStatus.PENDING);
        // the row stays: rejected observations are the negative examples
        assertThat(service.findAll(projectId, ObservationReviewStatus.REJECTED, null, null, null, null, null,
                PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
    }

    @Test
    void acceptCanAttachAnExistingDefectOnlyOnce() {
        InspectionDto inspection = inspections.create(request(List.of(), List.of(defect("Crack"))));
        UUID defectId = inspection.defects().get(0).id();
        // the defect already has its implicit observation; a second link is refused
        Observation pending = pending(inspection.id(), "Crack seen by drone");
        assertThatThrownBy(() -> service.review(pending.getId(), new ReviewObservationRequest(
                ObservationReviewDecision.ACCEPT, null, null,
                new ReviewObservationRequest.Outcome(ObservationOutcomeKind.DEFECT, null, null, defectId, null, null))))
                .isInstanceOf(InvalidRequestException.class);
    }

    // --------------------------------------------------------- implicit

    @Test
    void failedItemsAndDefectsGetAnObservationThroughTheExistingForms() {
        InspectionDto inspection = inspections.create(request(
                List.of(checkItem("Passes", CheckItemStatus.PASSED), checkItem("Fails", CheckItemStatus.FAILED)),
                List.of(defect("Honeycombing"))));

        List<Observation> rows = repository.findByInspectionScoped(inspection.id());
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(o -> {
            assertThat(o.getSource()).isEqualTo(ObservationSource.HUMAN);
            assertThat(o.getReviewStatus()).isEqualTo(ObservationReviewStatus.ACCEPTED);
            assertThat(o.getProjectId()).isEqualTo(projectId);
        });
        Observation forItem = rows.stream().filter(o -> o.getOutcomeKind() == ObservationOutcomeKind.CHECK_ITEM)
                .findFirst().orElseThrow();
        assertThat(forItem.getOutcomeRef()).isEqualTo(inspection.checkItems().get(1).id());
        assertThat(forItem.getTitle()).isEqualTo("Fails");
        Observation forDefect = rows.stream().filter(o -> o.getOutcomeKind() == ObservationOutcomeKind.DEFECT)
                .findFirst().orElseThrow();
        assertThat(forDefect.getOutcomeRef()).isEqualTo(inspection.defects().get(0).id());
        assertThat(forDefect.getSuggestedSeverity()).isEqualTo(DefectSeverity.MAJOR);
        assertThat(inspection.defects().get(0).observationId()).isEqualTo(forDefect.getId());
    }

    @Test
    void updateCarriesLinksOverToTheRebuiltChildrenAndAddsForNewFailures() {
        InspectionDto created = inspections.create(request(
                List.of(checkItem("Fails", CheckItemStatus.FAILED), checkItem("Passes", CheckItemStatus.PASSED)),
                List.of(defect("Honeycombing"))));
        UUID itemObservation = repository.findByOutcomeScoped(ObservationOutcomeKind.CHECK_ITEM,
                created.checkItems().get(0).id()).get(0).getId();
        UUID defectObservation = created.defects().get(0).observationId();
        entityManager.flush();
        entityManager.clear();

        InspectionDto updated = inspections.update(created.id(), update(
                List.of(checkItem("Fails", CheckItemStatus.FAILED), checkItem("Now fails", CheckItemStatus.FAILED)),
                List.of(defect("Honeycombing"), defect("New crack"))));
        entityManager.flush();
        entityManager.clear();

        List<Observation> rows = repository.findByInspectionScoped(updated.id());
        assertThat(rows).hasSize(4);
        // the old links now point at the rebuilt rows
        assertThat(repository.findByIdScoped(itemObservation).orElseThrow().getOutcomeRef())
                .isEqualTo(updated.checkItems().get(0).id());
        assertThat(updated.defects().get(0).observationId()).isEqualTo(defectObservation);
        assertThat(repository.findByIdScoped(defectObservation).orElseThrow().getOutcomeRef())
                .isEqualTo(updated.defects().get(0).id());
        // the newly failed item and the new defect got their own
        assertThat(repository.findByOutcomeScoped(ObservationOutcomeKind.CHECK_ITEM, updated.checkItems().get(1).id()))
                .hasSize(1);
        assertThat(updated.defects().get(1).observationId()).isNotNull().isNotEqualTo(defectObservation);
    }

    @Test
    void ncrInheritsTheDefectObservationOrGetsItsOwn() {
        InspectionDto inspection = inspections.create(request(List.of(), List.of(defect("Crack"))));
        UUID defectId = inspection.defects().get(0).id();
        UUID defectObservation = inspection.defects().get(0).observationId();

        NcrDto fromDefect = ncrs.create(new CreateNcrRequest(inspection.id(), defectId, "Crack NCR",
                "Crack at C4", DefectSeverity.MAJOR, null, LocalDate.of(2026, 10, 1)));
        assertThat(fromDefect.observationId()).isEqualTo(defectObservation);

        NcrDto whole = ncrs.create(new CreateNcrRequest(inspection.id(), null, "Housekeeping",
                "Debris on level 1", DefectSeverity.MINOR, null, null));
        assertThat(whole.observationId()).isNotNull().isNotEqualTo(defectObservation);
        Observation own = repository.findByIdScoped(whole.observationId()).orElseThrow();
        assertThat(own.getOutcomeKind()).isEqualTo(ObservationOutcomeKind.NCR);
        assertThat(own.getOutcomeRef()).isEqualTo(whole.id());
        assertThat(own.getTitle()).isEqualTo("Housekeeping");
    }

    // ------------------------------------------------- compliance suggestions

    @Test
    void approvingASuggestedInspectionAcceptsItsAiObservation() {
        UUID suggested = suggestedInspection("Building permit");
        Observation ai = repository.findByInspectionScoped(suggested).get(0);
        assertThat(ai.getReviewStatus()).isEqualTo(ObservationReviewStatus.PENDING);

        inspections.update(suggested, suggestionUpdate("Building permit", InspectionStatus.SCHEDULED));

        Observation reviewed = repository.findByIdScoped(ai.getId()).orElseThrow();
        assertThat(reviewed.getReviewStatus()).isEqualTo(ObservationReviewStatus.ACCEPTED);
        assertThat(reviewed.getReviewChanges()).isNull();
        assertThat(reviewed.getOutcomeRef()).isEqualTo(suggested);
        assertThat(events(ai.getId(), InspectionEventType.OBSERVATION_REVIEWED)).hasSize(1);

        // a later move is not a second decision
        inspections.update(suggested, suggestionUpdate("Building permit", InspectionStatus.CANCELLED));
        assertThat(repository.findByIdScoped(ai.getId()).orElseThrow().getReviewStatus())
                .isEqualTo(ObservationReviewStatus.ACCEPTED);
    }

    @Test
    void dismissingASuggestedInspectionRejectsItsAiObservation() {
        UUID suggested = suggestedInspection("Occupancy certificate");
        Observation ai = repository.findByInspectionScoped(suggested).get(0);

        inspections.update(suggested, suggestionUpdate("Occupancy certificate", InspectionStatus.CANCELLED));

        Observation reviewed = repository.findByIdScoped(ai.getId()).orElseThrow();
        assertThat(reviewed.getReviewStatus()).isEqualTo(ObservationReviewStatus.REJECTED);
        assertThat(reviewed.getReviewNote()).contains("dismissed");
    }

    @Test
    void editingASuggestedInspectionBeforeApprovalModifiesItsAiObservationWithTheDiff() {
        UUID suggested = suggestedInspection("Fire NOC");
        Observation ai = repository.findByInspectionScoped(suggested).get(0);

        inspections.update(suggested, suggestionUpdate("Fire NOC, block A", InspectionStatus.SUGGESTED));

        Observation reviewed = repository.findByIdScoped(ai.getId()).orElseThrow();
        assertThat(reviewed.getReviewStatus()).isEqualTo(ObservationReviewStatus.MODIFIED);
        assertThat(reviewed.getReviewChanges()).anySatisfy(c -> {
            assertThat(c).containsEntry("field", "title");
            assertThat(c).containsEntry("before", "Fire NOC");
            assertThat(c).containsEntry("after", "Fire NOC, block A");
        });
        // the proposal itself is untouched
        assertThat(reviewed.getTitle()).isEqualTo("Fire NOC");
    }

    // ---------------------------------------------------------- tenancy

    @Test
    void anotherTenantCannotSeeOrReviewTheObservation() {
        ObservationDto mine = service.create(new CreateObservationRequest(projectId, null, null, null,
                LocalDateTime.of(2026, 9, 12, 9, 0), "Mine", null, null, null, null));
        entityManager.flush();
        entityManager.clear();
        assertThat(repository.existsByIdAndOrganization_Id(mine.id(), orgAId)).isTrue();

        disableOrgFilter();
        TenantContext.setCurrentOrgId(orgBId);
        enableOrgFilter(orgBId);

        assertThatThrownBy(() -> service.findById(mine.id())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.review(mine.id(), new ReviewObservationRequest(
                ObservationReviewDecision.REJECT, "not ours", null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(service.findAll(projectId, null, null, null, null, null, null, PageRequest.of(0, 10))
                .getTotalElements()).isZero();
        assertThat(repository.existsByIdAndOrganization_Id(mine.id(), orgBId)).isFalse();
    }

    // ---------------------------------------------------------- helpers

    /** A compliance suggestion as the generator leaves it, with its pending AI observation. */
    private UUID suggestedInspection(String title) {
        Inspection i = new Inspection();
        i.setInspectionNumber("INSP-S-" + title.hashCode());
        i.setTitle(title);
        i.setType(InspectionType.COMPLIANCE);
        i.setCategory(InspectionCategory.COMPLIANCE);
        i.setStatus(InspectionStatus.SUGGESTED);
        i.setOrigin(InspectionOrigin.AI_GENERATED);
        i.setProjectId(projectId);
        i.setComplianceRuleRef("TN-01");
        i.setAiRationale("Required before work starts");
        i.setOrganization(entityManager.getReference(Organization.class, orgAId));
        entityManager.persist(i);
        entityManager.flush();
        service.recordAiSuggestion(i, "llama3.3-70b-instruct", "", "compliance-generator");
        entityManager.flush();
        entityManager.clear();
        return i.getId();
    }

    private UpdateInspectionRequest suggestionUpdate(String title, InspectionStatus status) {
        return new UpdateInspectionRequest(title, InspectionType.COMPLIANCE, InspectionCategory.COMPLIANCE, null, null,
                status, null, projectId, null, null, null, null, null, null, null, null, null, null,
                null, List.of(), null, null, List.of(), List.of(), null);
    }

    private Observation pending(UUID inspectionId, String title) {
        Observation o = new Observation();
        o.setOrganization(entityManager.getReference(Organization.class, orgAId));
        o.setProjectId(projectId);
        o.setInspectionId(inspectionId);
        o.setSource(ObservationSource.DRONE);
        o.setSourceDeviceId("drone-7");
        o.setObservedAt(LocalDateTime.of(2026, 9, 12, 8, 30));
        o.setTitle(title);
        o.setDescription(title + " described");
        o.setReviewStatus(ObservationReviewStatus.PENDING);
        o.setOutcomeKind(ObservationOutcomeKind.NONE);
        entityManager.persist(o);
        entityManager.flush();
        return o;
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

    private CreateInspectionRequest request(List<InspectionCheckItemRequest> items,
                                            List<InspectionDefectRequest> defects) {
        return new CreateInspectionRequest("Slab check", InspectionType.QUALITY, null, "rcc", null,
                projectId, "Block A, Level 3", "Slab and columns", "STR-03-REV2",
                LocalDate.of(2026, 8, 20), "09:30", null, null, 90, 100L, 200L, "Client Rep",
                List.of("Site Engineer"), "Clear", "32C", items, defects, zone);
    }

    private UpdateInspectionRequest update(List<InspectionCheckItemRequest> items,
                                           List<InspectionDefectRequest> defects) {
        return new UpdateInspectionRequest("Slab check", InspectionType.QUALITY, null, "rcc", null,
                InspectionStatus.IN_PROGRESS, null, projectId, "Block A, Level 3", "Slab and columns",
                "STR-03-REV2", LocalDate.of(2026, 8, 20), "09:30", null, null, 90, 100L, 200L,
                "Client Rep", List.of("Site Engineer"), "Clear", "32C", items, defects, zone);
    }

    private static InspectionCheckItemRequest checkItem(String checkPoint, CheckItemStatus status) {
        return new InspectionCheckItemRequest("Structural", checkPoint, "Within tolerance",
                status, "measured off", false, null, null, null, null, null, null, "high", null);
    }

    private static InspectionDefectRequest defect(String description) {
        return new InspectionDefectRequest("Structural", description, DefectSeverity.MAJOR,
                "Column C4, ground floor", null, "Chip out and re-pour", "ABC Contractors",
                LocalDate.of(2026, 9, 1), DefectStatus.OPEN, null, null);
    }

    private void enableOrgFilter(Long orgId) {
        entityManager.unwrap(Session.class).enableFilter("orgFilter").setParameter("organizationId", orgId);
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
