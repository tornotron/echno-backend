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
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.modules.inspections.dtos.CreateInspectionRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionCheckItemRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionDefectRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionDto;
import org.tornotron.echno_backend.modules.inspections.dtos.UpdateInspectionRequest;
import org.tornotron.echno_backend.modules.inspections.mapper.ChecklistTemplateMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.DefectPhotoAnnotationMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.InspectionMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.NcrMapperImpl;
import org.tornotron.echno_backend.modules.inspections.service.ChecklistTemplateService;
import org.tornotron.echno_backend.modules.inspections.service.DefectAnnotationService;
import org.tornotron.echno_backend.modules.inspections.service.InspectionService;
import org.tornotron.echno_backend.modules.inspections.service.NcrService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.spatial.SpatialLevel;
import org.tornotron.echno_backend.project.spatial.SpatialNodeArchivedException;
import org.tornotron.echno_backend.project.spatial.SpatialNodeService;
import org.tornotron.echno_backend.project.spatial.dto.CreateSpatialNodeRequest;
import org.tornotron.echno_backend.project.spatial.dto.SpatialPathSegment;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventRecorder;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventService;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The site-structure reference on an inspection, its defects and its check points: it round
 * trips with a breadcrumb, the free text stays as the fallback and as a note beside it, a
 * node of another project or an archived node is refused on write while a stored reference
 * survives archiving, and the list filter returns the subtree.
 *
 * <p>Same annotations and {@code @Import} list as {@link InspectionServiceIT}, to the letter,
 * so the context cache hands both one Spring context.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({InspectionService.class, SpatialNodeService.class, InspectionMapperImpl.class,
        ChecklistTemplateService.class, ChecklistTemplateMapperImpl.class,
        NcrService.class, NcrMapperImpl.class,
        InspectionEventRecorder.class, InspectionEventService.class,
        DefectAnnotationService.class, DefectPhotoAnnotationMapperImpl.class,
        UserContextService.class,
        TenantEntityHelper.class, EntryNumberGenerator.class})
class InspectionSpatialReferenceIT extends AbstractIntegrationTest {

    @Autowired
    private InspectionService service;

    @Autowired
    private SpatialNodeService spatial;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private Long orgAId;
    private Long orgBId;
    private Long projectId;
    private Long otherProjectId;

    private UUID building;
    private UUID floor1;
    private UUID zone1;
    private UUID element1;
    private UUID floor2;

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
            Project other = new Project();
            other.setProjectName("Tower B");
            other.setOrganization(orgA);
            entityManager.persist(other);
            entityManager.flush();
            orgAId = orgA.getId();
            orgBId = orgB.getId();
            projectId = project.getId();
            otherProjectId = other.getId();
        });
        TenantContext.setCurrentOrgId(orgAId);
        enableOrgFilter(orgAId);

        building = node(projectId, null, SpatialLevel.BUILDING, "B1");
        floor1 = node(projectId, building, SpatialLevel.FLOOR, "L01");
        zone1 = node(projectId, floor1, SpatialLevel.ZONE, "Z1");
        element1 = node(projectId, zone1, SpatialLevel.ELEMENT, "C4");
        floor2 = node(projectId, building, SpatialLevel.FLOOR, "L02");
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
            deleteForOrgs("DELETE FROM inspection_defects WHERE inspection_id IN "
                    + "(SELECT id FROM inspections WHERE organization_id IN (:a,:b))");
            deleteForOrgs("DELETE FROM inspection_check_items WHERE inspection_id IN "
                    + "(SELECT id FROM inspections WHERE organization_id IN (:a,:b))");
            deleteForOrgs("DELETE FROM inspections WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM project_spatial_node WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM document_sequence WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM project WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM organization WHERE id IN (:a,:b)");
        });
    }

    @Test
    void referenceRoundTripsWithBreadcrumbAndFreeTextStays() {
        InspectionDto placed = service.create(request("Slab check", projectId, floor1,
                List.of(checkItem("Column alignment", element1)),
                List.of(defect("Honeycombing", zone1))));

        assertThat(placed.spatialNodeId()).isEqualTo(floor1);
        assertThat(placed.spatialPath()).extracting(SpatialPathSegment::code).containsExactly("B1", "L01");
        assertThat(placed.location()).isEqualTo("Block A, Level 3");
        assertThat(placed.checkItems().get(0).spatialNodeId()).isEqualTo(element1);
        assertThat(placed.checkItems().get(0).spatialPath()).extracting(SpatialPathSegment::code)
                .containsExactly("B1", "L01", "Z1", "C4");
        assertThat(placed.defects().get(0).spatialNodeId()).isEqualTo(zone1);
        assertThat(placed.defects().get(0).spatialPath()).extracting(SpatialPathSegment::level)
                .containsExactly(SpatialLevel.BUILDING, SpatialLevel.FLOOR, SpatialLevel.ZONE);

        entityManager.flush();
        entityManager.clear();
        InspectionDto read = service.findById(placed.id());
        assertThat(read.spatialPath()).extracting(SpatialPathSegment::code).containsExactly("B1", "L01");
        assertThat(read.defects().get(0).spatialPath()).hasSize(3);

        // the pre-hierarchy state: no node, free text only, nothing derived
        InspectionDto unplaced = service.create(request("Text only", projectId, null,
                List.of(checkItem("Rebar spacing", null)), List.of(defect("Crack", null))));
        assertThat(unplaced.spatialNodeId()).isNull();
        assertThat(unplaced.spatialPath()).isEmpty();
        assertThat(unplaced.location()).isEqualTo("Block A, Level 3");
        assertThat(unplaced.checkItems().get(0).spatialPath()).isEmpty();
        assertThat(unplaced.defects().get(0).spatialNodeId()).isNull();
    }

    @Test
    void wrongProjectAndArchivedNodesAreRefusedButAStoredReferenceSurvivesArchiving() {
        UUID foreign = node(otherProjectId, null, SpatialLevel.BUILDING, "X1");
        assertThatThrownBy(() -> service.create(request("Wrong project", projectId, foreign, List.of(), List.of())))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.create(request("Wrong project on defect", projectId, null,
                List.of(), List.of(defect("Crack", foreign)))))
                .isInstanceOf(ResourceNotFoundException.class);

        InspectionDto placed = service.create(request("On zone", projectId, zone1,
                List.of(checkItem("Check", element1)), List.of()));
        spatial.archive(projectId, floor1);
        entityManager.flush();
        entityManager.clear();

        // a new reference to the archived subtree is refused
        assertThatThrownBy(() -> service.create(request("Late", projectId, zone1, List.of(), List.of())))
                .isInstanceOf(SpatialNodeArchivedException.class);
        assertThatThrownBy(() -> service.create(request("Late item", projectId, null,
                List.of(checkItem("Check", element1)), List.of())))
                .isInstanceOf(SpatialNodeArchivedException.class);

        // the stored one still reads with its breadcrumb and can be re-saved unchanged
        InspectionDto read = service.findById(placed.id());
        assertThat(read.spatialNodeId()).isEqualTo(zone1);
        assertThat(read.spatialPath()).extracting(SpatialPathSegment::code).containsExactly("B1", "L01", "Z1");
        InspectionDto updated = service.update(placed.id(), update("On zone, edited", projectId, zone1));
        assertThat(updated.spatialNodeId()).isEqualTo(zone1);

        // and can be cleared back to free text
        assertThat(service.update(placed.id(), update("Cleared", projectId, null)).spatialNodeId()).isNull();
    }

    @Test
    void listFilterReturnsTheSubtree() {
        service.create(request("On L01 zone", projectId, zone1, List.of(), List.of()));
        service.create(request("On L02", projectId, floor2, List.of(), List.of()));
        service.create(request("Unplaced", projectId, null, List.of(), List.of()));
        entityManager.flush();
        entityManager.clear();

        assertThat(titles(list(building))).containsExactlyInAnyOrder("On L01 zone", "On L02");
        assertThat(titles(list(floor1))).containsExactly("On L01 zone");
        assertThat(titles(list(element1))).isEmpty();
        assertThat(titles(list(null))).hasSize(3);
        // a node the caller cannot see matches nothing rather than everything
        assertThat(titles(list(UUID.randomUUID()))).isEmpty();
    }

    private Page<InspectionDto> list(UUID nodeId) {
        return service.findAll(projectId, null, null, null, null, null, nodeId, PageRequest.of(0, 10));
    }

    private static List<String> titles(Page<InspectionDto> page) {
        return page.getContent().stream().map(InspectionDto::title).toList();
    }

    private UUID node(Long project, UUID parent, SpatialLevel level, String code) {
        return spatial.create(project, new CreateSpatialNodeRequest(parent, level, code, code,
                null, null, null, null, null)).id();
    }

    private static CreateInspectionRequest request(String title, Long project, UUID nodeId,
                                                   List<InspectionCheckItemRequest> items,
                                                   List<InspectionDefectRequest> defects) {
        return new CreateInspectionRequest(title, InspectionType.QUALITY, null, InspectionTrade.RCC,
                project, "Block A, Level 3", "Slab and columns", "STR-03-REV2",
                LocalDate.of(2026, 8, 20), "09:30", null, null, 90, 100L, 200L, "Client Rep",
                List.of("Site Engineer"), "Clear", "32C", items, defects, nodeId);
    }

    private static UpdateInspectionRequest update(String title, Long project, UUID nodeId) {
        return new UpdateInspectionRequest(title, InspectionType.QUALITY, null, InspectionTrade.RCC,
                InspectionStatus.SCHEDULED, null, project, "Block A, Level 3", "Slab and columns",
                "STR-03-REV2", LocalDate.of(2026, 8, 20), "09:30", null, null, 90, 100L, 200L,
                "Client Rep", List.of("Site Engineer"), "Clear", "32C", List.of(), List.of(), nodeId);
    }

    private static InspectionCheckItemRequest checkItem(String checkPoint, UUID nodeId) {
        return new InspectionCheckItemRequest("Structural", checkPoint, "Within tolerance",
                CheckItemStatus.PASSED, null, false, null, null, null, null, null, null, "high", nodeId);
    }

    private static InspectionDefectRequest defect(String description, UUID nodeId) {
        return new InspectionDefectRequest("Structural", description, DefectSeverity.MAJOR,
                "Column C4, ground floor", null, "Chip out and re-pour", "ABC Contractors",
                LocalDate.of(2026, 9, 1), DefectStatus.OPEN, null, nodeId);
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
