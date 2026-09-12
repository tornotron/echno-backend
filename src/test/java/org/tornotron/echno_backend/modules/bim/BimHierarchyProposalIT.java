package org.tornotron.echno_backend.modules.bim;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.bim.domain.BimElement;
import org.tornotron.echno_backend.modules.bim.domain.BimImportJob;
import org.tornotron.echno_backend.modules.bim.domain.BimModelVersion;
import org.tornotron.echno_backend.modules.bim.dto.BimModelDto;
import org.tornotron.echno_backend.modules.bim.dto.CreateBimModelRequest;
import org.tornotron.echno_backend.modules.bim.dto.BimHierarchyProposalDto;
import org.tornotron.echno_backend.modules.bim.dto.ConfirmBimHierarchyRequest;
import org.tornotron.echno_backend.modules.bim.hierarchy.BimHierarchyProposalListener;
import org.tornotron.echno_backend.modules.bim.hierarchy.BimHierarchyService;
import org.tornotron.echno_backend.modules.bim.importer.BimArtifactReader;
import org.tornotron.echno_backend.modules.bim.importer.BimImportIngestor;
import org.tornotron.echno_backend.modules.bim.importer.BimImportPipeline;
import org.tornotron.echno_backend.modules.bim.mapper.BimMapperImpl;
import org.tornotron.echno_backend.modules.bim.repository.BimElementRepository;
import org.tornotron.echno_backend.modules.bim.repository.BimImportJobRepository;
import org.tornotron.echno_backend.modules.bim.repository.BimModelVersionRepository;
import org.tornotron.echno_backend.modules.bim.service.BimElementService;
import org.tornotron.echno_backend.modules.bim.service.BimModelService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.spatial.SpatialLevel;
import org.tornotron.echno_backend.project.spatial.dto.SpatialTreeNodeDto;
import org.tornotron.echno_backend.project.spatial.SpatialNodeService;
import org.tornotron.echno_backend.project.spatial.dto.CreateSpatialNodeRequest;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * The hierarchy proposal against a real database: built at ingestion, a proposal until
 * confirmed, matched to existing nodes by GlobalId, and idempotent on a second confirmation.
 * Wiring as for the element identity rules: a first import inserts, a second one
 * keeps row ids for matched GlobalIds, retires the missing, inserts the new, and a merge
 * carries a construction element from a retired row to its replacement.
 *
 * <p>Runs without a test transaction so each service call commits or rolls back on its own,
 * which is what the failure test is about: a broken artifact must leave no element behind.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({BimImportPipeline.class, BimImportIngestor.class, BimModelService.class, BimElementService.class,
        BimHierarchyService.class, BimHierarchyProposalListener.class,
        BimMapperImpl.class, SpatialNodeService.class, UserContextService.class, TenantEntityHelper.class,
        BimHierarchyProposalIT.Artifacts.class})
class BimHierarchyProposalIT extends AbstractIntegrationTest {

    @TestConfiguration
    static class Artifacts {
        static final Map<String, byte[]> STORE = new HashMap<>();

        @Bean
        BimArtifactReader bimArtifactReader() {
            return key -> {
                byte[] bytes = STORE.get(key);
                if (bytes == null) {
                    throw new FileNotFoundException(key);
                }
                return new ByteArrayInputStream(bytes);
            };
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

    @Autowired private BimImportPipeline pipeline;
    @Autowired private BimModelService modelService;
    @Autowired private BimElementService elementService;
    @Autowired private BimHierarchyService hierarchy;
    @Autowired private SpatialNodeService spatial;
    @Autowired private BimModelVersionRepository versions;
    @Autowired private BimImportJobRepository jobs;
    @Autowired private BimElementRepository elements;
    @PersistenceContext private EntityManager entityManager;
    @Autowired private PlatformTransactionManager txManager;

    private Long orgAId;
    private Long orgBId;
    private Long projectId;
    private UUID modelId;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        Artifacts.STORE.clear();
        inCommittedTx(() -> {
            Organization orgA = persistOrganization("Pipe Org A");
            Organization orgB = persistOrganization("Pipe Org B");
            Project project = new Project();
            project.setProjectName("Tower P");
            project.setOrganization(orgA);
            entityManager.persist(project);
            orgAId = orgA.getId();
            orgBId = orgB.getId();
            projectId = project.getId();
        });
        TenantContext.setCurrentOrgId(orgAId);
        modelId = modelService.create(projectId, new CreateBimModelRequest("ARC", null)).id();
    }

    @AfterEach
    void removeCommittedRows() {
        TenantContext.clear();
        if (orgAId == null && orgBId == null) {
            return;
        }
        inCommittedTx(() -> {
            deleteForOrgs("DELETE FROM bim_import_jobs WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM bim_elements WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM bim_model_versions WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM bim_models WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM project_spatial_node WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM project WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM organization WHERE id IN (:a,:b)");
        });
    }

    @Test
    void ingestionProposesTheTreeMatchesExistingNodesAndCreatesNothing() {
        UUID existingBuilding = spatial.create(projectId, new CreateSpatialNodeRequest(null, SpatialLevel.BUILDING,
                "A", "Block A (hand made)", null, null, null, "BLDG", null)).id();
        UUID v1 = version(1);
        artifacts(v1, List.of(
                line("WALL-1", "IfcWallStandardCase", "Wall 1", "STOREY-1", "SPACE-1"),
                line("COL-1", "IfcColumn", "Column 1", "STOREY-1", null),
                line("SLAB-9", "IfcSlab", "Slab 9", "STOREY-2", null),
                line("CHAIR-1", "IfcFurnishingElement", "Chair", "STOREY-1", "SPACE-1")), 2);

        pipeline.ingest(doneJob(v1));

        BimHierarchyProposalDto proposal = hierarchy.get(modelId, v1);
        assertThat(proposal.confirmedAt()).isNull();
        assertThat(proposal.counts()).containsEntry("buildings", 1).containsEntry("floors", 2)
                .containsEntry("zones", 3).containsEntry("elements", 3);
        assertThat(proposal.buildings()).singleElement().satisfies(b -> {
            assertThat(b.matchedNodeId()).as("matched by GlobalId, not by name").isEqualTo(existingBuilding);
            assertThat(b.floors()).extracting(f -> f.code()).containsExactly("Level-01", "Level-02");
            assertThat(b.floors().get(0).zones()).extracting(z -> z.code()).containsExactly("L01-Z1", "Level-01");
            assertThat(b.floors().get(0).zones().get(0).elements()).extracting(e -> e.globalId()).containsExactly("WALL-1");
            assertThat(b.floors().get(0).zones().get(1).elements()).extracting(e -> e.globalId()).containsExactly("COL-1");
            assertThat(b.floors().get(1).zones()).singleElement().satisfies(z -> assertThat(z.defaultZone()).isTrue());
        });
        assertThat(modelService.getVersion(modelId, v1).hierarchyProposed()).isTrue();

        List<SpatialTreeNodeDto> tree = spatial.getTree(projectId, false);
        assertThat(tree).as("a proposal creates nothing").hasSize(1);
        assertThat(tree.get(0).children()).isEmpty();
        assertThat(elements.findByModelId(modelId)).allSatisfy(e -> assertThat(e.getSpatialNodeId()).isNull());
    }

    @Test
    void confirmingCreatesOrMatchesEveryNodeLinksTheElementsAndIsIdempotent() {
        UUID existingBuilding = spatial.create(projectId, new CreateSpatialNodeRequest(null, SpatialLevel.BUILDING,
                "A", "Block A", null, null, null, "BLDG", null)).id();
        UUID v1 = version(1);
        artifacts(v1, List.of(
                line("WALL-1", "IfcWallStandardCase", "Wall 1", "STOREY-1", "SPACE-1"),
                line("COL-1", "IfcColumn", "Column 1", "STOREY-1", null),
                line("SLAB-9", "IfcSlab", "Slab 9", "STOREY-2", null),
                line("CHAIR-1", "IfcFurnishingElement", "Chair", "STOREY-1", "SPACE-1")), 2);
        pipeline.ingest(doneJob(v1));

        BimHierarchyProposalDto confirmed = hierarchy.confirm(modelId, v1, null);

        assertThat(confirmed.confirmedAt()).isNotNull();
        // building matched; 2 floors + 1 zone + 2 default zones + 3 elements created (default zones count as matched)
        assertThat(confirmed.confirmation().nodesCreated()).isEqualTo(6);
        assertThat(confirmed.confirmation().nodesMatched()).isEqualTo(3);
        assertThat(confirmed.confirmation().elementsLinked()).isEqualTo(3);
        assertThat(confirmed.buildings().get(0).matchedNodeId()).isEqualTo(existingBuilding);
        assertThat(confirmed.buildings().get(0).floors().get(0).zones().get(0).elements().get(0).matchedNodeId()).isNotNull();

        List<SpatialTreeNodeDto> tree = spatial.getTree(projectId, false);
        assertThat(tree).as("the hand-made building was matched, not duplicated").hasSize(1);
        assertThat(tree.get(0).children()).extracting(SpatialTreeNodeDto::code).containsExactly("Level-01", "Level-02");
        UUID wallNode = elements.findByModelIdAndGlobalId(modelId, "WALL-1").orElseThrow().getSpatialNodeId();
        assertThat(wallNode).isNotNull();
        assertThat(spatial.getNode(projectId, wallNode)).satisfies(n -> {
            assertThat(n.bimElementGuid()).isEqualTo("WALL-1");
            assertThat(n.elementType()).isEqualTo("wall");
            assertThat(n.spatialPath()).extracting(p -> p.code()).containsExactly("A", "Level-01", "L01-Z1", "Wall-1");
        });
        assertThat(elements.findByModelIdAndGlobalId(modelId, "COL-1").orElseThrow().getSpatialNodeId()).isNotNull();
        assertThat(elements.findByModelIdAndGlobalId(modelId, "CHAIR-1").orElseThrow().getSpatialNodeId())
                .as("furniture stays a bim_element only").isNull();
        assertThat(modelService.getVersion(modelId, v1).hierarchyConfirmedAt()).isNotNull();

        BimHierarchyProposalDto again = hierarchy.confirm(modelId, v1, null);
        assertThat(again.confirmation().nodesCreated()).isZero();
        assertThat(again.confirmation().elementsLinked()).isZero();
        assertThat(spatial.getTree(projectId, false)).hasSize(1);
    }

    @Test
    void anArchivedNodeWithTheGuidIsRestoredAndMatchedNotDuplicated() {
        UUID archived = spatial.create(projectId, new CreateSpatialNodeRequest(null, SpatialLevel.BUILDING,
                "OLD", "Old block", null, null, null, "BLDG", null)).id();
        spatial.archive(projectId, archived);
        UUID v1 = version(1);
        artifacts(v1, List.of(line("WALL-1", "IfcWallStandardCase", "Wall 1", "STOREY-1", "SPACE-1")), 1);
        pipeline.ingest(doneJob(v1));

        BimHierarchyProposalDto confirmed = hierarchy.confirm(modelId, v1, new ConfirmBimHierarchyRequest(false, null));

        assertThat(confirmed.buildings().get(0).matchedNodeId()).isEqualTo(archived);
        List<SpatialTreeNodeDto> tree = spatial.getTree(projectId, false);
        assertThat(tree).singleElement().satisfies(b -> {
            assertThat(b.id()).isEqualTo(archived);
            assertThat(b.children()).extracting(SpatialTreeNodeDto::code).containsExactly("Level-01", "Level-02");
            assertThat(b.children().get(1).children()).as("a floor with no spaces still gets its default zone")
                    .singleElement().satisfies(z -> assertThat(z.level()).isEqualTo(SpatialLevel.ZONE));
        });
    }

    @Test
    void confirmingWithoutElementsStopsAtZonesAndASubsetLinksOnlyThose() {
        UUID v1 = version(1);
        artifacts(v1, List.of(
                line("WALL-1", "IfcWallStandardCase", "Wall 1", "STOREY-1", "SPACE-1"),
                line("COL-1", "IfcColumn", "Column 1", "STOREY-1", "SPACE-1")), 1);
        pipeline.ingest(doneJob(v1));

        BimHierarchyProposalDto structureOnly = hierarchy.confirm(modelId, v1, new ConfirmBimHierarchyRequest(false, null));
        assertThat(structureOnly.confirmation().elementsLinked()).isZero();
        assertThat(elements.findByModelId(modelId)).allSatisfy(e -> assertThat(e.getSpatialNodeId()).isNull());

        BimHierarchyProposalDto subset = hierarchy.confirm(modelId, v1, new ConfirmBimHierarchyRequest(true, List.of("COL-1")));
        assertThat(subset.confirmation().elementsLinked()).isEqualTo(1);
        assertThat(subset.confirmation().elementsSkipped()).isEqualTo(1);
        assertThat(elements.findByModelIdAndGlobalId(modelId, "COL-1").orElseThrow().getSpatialNodeId()).isNotNull();
        assertThat(elements.findByModelIdAndGlobalId(modelId, "WALL-1").orElseThrow().getSpatialNodeId()).isNull();
    }

    // ------------------------------------------------------------------------------------

    private UUID version(int number) {
        BimModelVersion v = new BimModelVersion();
        v.setOrganization(orgRef());
        v.setModelId(modelId);
        v.setProjectId(projectId);
        v.setVersionNumber(number);
        v.setStatus(BimVersionStatus.PROCESSING);
        v.setSourceKey("bim/" + modelId + "/pending/source.ifc");
        v.setSourceFilename("tower-p-v" + number + ".ifc");
        return versions.saveAndFlush(v).getId();
    }

    private UUID doneJob(UUID versionId) {
        BimImportJob j = new BimImportJob();
        j.setOrganization(orgRef());
        j.setModelId(modelId);
        j.setVersionId(versionId);
        j.setStatus(BimImportJobStatus.DONE);
        j.setSourceKey(BimStorageLayout.sourceKey(modelId, versionId));
        j.setOutputPrefix(BimStorageLayout.prefix(modelId, versionId));
        j.setQueuedAt(LocalDateTime.now().minusMinutes(5));
        j.setStartedAt(LocalDateTime.now().minusMinutes(4));
        j.setFinishedAt(LocalDateTime.now().minusMinutes(1));
        j.setAttempt(1);
        return jobs.saveAndFlush(j).getId();
    }

    private void artifacts(UUID versionId, List<String> lines, int storeys) {
        Artifacts.STORE.put(BimStorageLayout.elementsKey(modelId, versionId),
                (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        Artifacts.STORE.put(BimStorageLayout.structureKey(modelId, versionId),
                ("{\"project\":{\"globalId\":\"PROJ\",\"name\":\"Tower P\"},\"sites\":[{\"globalId\":\"SITE\",\"name\":\"Site\","
                        + "\"buildings\":[{\"globalId\":\"BLDG\",\"name\":\"Block A\",\"storeys\":["
                        + "{\"globalId\":\"STOREY-1\",\"name\":\"Level 01\",\"elevation\":0.0,\"spaces\":[{\"globalId\":\"SPACE-1\",\"name\":\"L01-Z1\",\"longName\":\"Lobby\"}]},"
                        + "{\"globalId\":\"STOREY-2\",\"name\":\"Level 02\",\"elevation\":3.0,\"spaces\":[]}]}]}]}")
                        .getBytes(StandardCharsets.UTF_8));
        Artifacts.STORE.put(BimStorageLayout.metaKey(modelId, versionId),
                ("{\"ifcSchema\":\"IFC4\",\"units\":{\"length\":\"METRE\"},\"elementCount\":" + lines.size()
                        + ",\"storeyCount\":" + storeys + ",\"storeys\":[],\"coarseTile\":\"tiles/coarse.glb\","
                        + "\"workerVersion\":\"0.1.0\"}").getBytes(StandardCharsets.UTF_8));
    }

    private static String line(String globalId, String type, String name, String storey, String space) {
        return "{\"globalId\":\"" + globalId + "\",\"ifcType\":\"" + type + "\",\"name\":\"" + name
                + "\",\"storeyGlobalId\":\"" + storey + "\",\"spaceGlobalId\":"
                + (space == null ? "null" : "\"" + space + "\"") + ","
                + "\"bbox\":{\"min\":[0,0,0],\"max\":[1,1,3]},\"properties\":{\"Pset_Common\":{\"IsExternal\":false}}}";
    }

    private UUID elementNode(String code, String guid) {
        UUID building = spatial.create(projectId, new CreateSpatialNodeRequest(null, SpatialLevel.BUILDING,
                "B-" + code, "Block", null, null, null, null, null)).id();
        UUID floor = spatial.create(projectId, new CreateSpatialNodeRequest(building, SpatialLevel.FLOOR,
                "F-" + code, "Floor", null, 1, null, null, null)).id();
        UUID zone = spatial.create(projectId, new CreateSpatialNodeRequest(floor, SpatialLevel.ZONE,
                "Z-" + code, "Zone", null, null, null, null, null)).id();
        return spatial.create(projectId, new CreateSpatialNodeRequest(zone, SpatialLevel.ELEMENT,
                code, "Element " + code, null, null, "wall", guid, null)).id();
    }



    private void link(UUID elementId, UUID nodeId) {
        inCommittedTx(() -> entityManager.createNativeQuery(
                        "UPDATE bim_elements SET spatial_node_id = :n WHERE id = :id")
                .setParameter("n", nodeId).setParameter("id", elementId).executeUpdate());
    }

    private Organization orgRef() {
        Organization org = new Organization();
        org.setId(orgAId);
        return org;
    }

    private void deleteForOrgs(String sql) {
        entityManager.createNativeQuery(sql).setParameter("a", orgAId).setParameter("b", orgBId).executeUpdate();
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
