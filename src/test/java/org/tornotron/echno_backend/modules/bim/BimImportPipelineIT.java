package org.tornotron.echno_backend.modules.bim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.bim.domain.BimElement;
import org.tornotron.echno_backend.modules.bim.domain.BimImportJob;
import org.tornotron.echno_backend.modules.bim.domain.BimModelVersion;
import org.tornotron.echno_backend.modules.bim.dto.BimElementDto;
import org.tornotron.echno_backend.modules.bim.dto.BimModelDto;
import org.tornotron.echno_backend.modules.bim.dto.CreateBimModelRequest;
import org.tornotron.echno_backend.modules.bim.dto.MergeBimElementRequest;
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
import org.tornotron.echno_backend.project.spatial.SpatialNodeService;
import org.tornotron.echno_backend.project.spatial.dto.CreateSpatialNodeRequest;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * The element identity rules against a real database: a first import inserts, a second one
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
        BimMapperImpl.class, SpatialNodeService.class, UserContextService.class, TenantEntityHelper.class,
        BimImportPipelineIT.Artifacts.class})
class BimImportPipelineIT extends AbstractIntegrationTest {

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
    void firstImportInsertsEveryElementAndMakesTheVersionCurrent() {
        UUID v1 = version(1);
        UUID job = doneJob(v1);
        artifacts(v1, List.of(
                line("WALL-1", "IfcWallStandardCase", "Wall 1", "STOREY-1"),
                line("COL-1", "IfcColumn", "Column 1", "STOREY-1"),
                line("CHAIR-1", "IfcFurnishingElement", "Chair", "STOREY-1")), 1);

        pipeline.ingest(job);

        BimModelVersion version = versions.findById(v1).orElseThrow();
        assertThat(version.getStatus()).isEqualTo(BimVersionStatus.READY);
        assertThat(version.getElementCount()).isEqualTo(3);
        assertThat(version.getStoreyCount()).isEqualTo(1);
        assertThat(version.getIfcSchema()).isEqualTo("IFC4");
        assertThat(version.getMeta()).containsEntry("workerVersion", "0.1.0");
        assertThat(modelService.get(modelId).currentVersionId()).isEqualTo(v1);
        assertThat(jobs.findById(job).orElseThrow().getIngestedAt()).isNotNull();
        assertThat(modelService.listElements(modelId, "STOREY-1", false, 0, 10).totalElements()).isEqualTo(3);
        assertThat(elements.findByModelIdAndGlobalId(modelId, "WALL-1")).get()
                .satisfies(e -> {
                    assertThat(e.getFirstSeenVersionId()).isEqualTo(v1);
                    assertThat(e.getLastSeenVersionId()).isEqualTo(v1);
                    assertThat(e.getProperties()).containsKey("Pset_Common");
                });
    }

    @Test
    void reimportKeepsIdsForMatchedGlobalIdsRetiresTheMissingAndInsertsTheNew() {
        UUID v1 = version(1);
        artifacts(v1, List.of(
                line("WALL-1", "IfcWallStandardCase", "Wall 1", "STOREY-1"),
                line("COL-1", "IfcColumn", "Column 1", "STOREY-1")), 1);
        pipeline.ingest(doneJob(v1));
        BimElement wall = elements.findByModelIdAndGlobalId(modelId, "WALL-1").orElseThrow();
        UUID wallRowId = wall.getId();
        UUID node = elementNode("W1", "WALL-1");
        link(wallRowId, node);

        UUID v2 = version(2);
        artifacts(v2, List.of(
                line("WALL-1", "IfcWallStandardCase", "Wall 1 (moved)", "STOREY-2"),
                line("SLAB-9", "IfcSlab", "Slab 9", "STOREY-2")), 2);
        pipeline.ingest(doneJob(v2));

        BimElement wallAgain = elements.findByModelIdAndGlobalId(modelId, "WALL-1").orElseThrow();
        assertThat(wallAgain.getId()).as("the matched row keeps its id").isEqualTo(wallRowId);
        assertThat(wallAgain.getName()).isEqualTo("Wall 1 (moved)");
        assertThat(wallAgain.getStoreyGlobalId()).isEqualTo("STOREY-2");
        assertThat(wallAgain.getSpatialNodeId()).as("the association survives").isEqualTo(node);
        assertThat(wallAgain.getFirstSeenVersionId()).isEqualTo(v1);
        assertThat(wallAgain.getLastSeenVersionId()).isEqualTo(v2);
        assertThat(wallAgain.isRetired()).isFalse();

        BimElement column = elements.findByModelIdAndGlobalId(modelId, "COL-1").orElseThrow();
        assertThat(column.isRetired()).as("missing from v2, flagged not deleted").isTrue();
        assertThat(column.getLastSeenVersionId()).isEqualTo(v1);

        BimElement slab = elements.findByModelIdAndGlobalId(modelId, "SLAB-9").orElseThrow();
        assertThat(slab.getFirstSeenVersionId()).isEqualTo(v2);

        assertThat(modelService.listElements(modelId, null, false, 0, 10).totalElements()).isEqualTo(2);
        assertThat(modelService.listElements(modelId, null, true, 0, 10).totalElements()).isEqualTo(3);
        assertThat(modelService.get(modelId).currentVersionId()).isEqualTo(v2);
    }

    @Test
    void mergeCarriesTheConstructionElementFromARetiredRowToItsReplacement() {
        UUID v1 = version(1);
        artifacts(v1, List.of(line("COL-OLD", "IfcColumn", "Column", "STOREY-1")), 1);
        pipeline.ingest(doneJob(v1));
        BimElement old = elements.findByModelIdAndGlobalId(modelId, "COL-OLD").orElseThrow();
        UUID node = elementNode("C1", "COL-OLD");
        link(old.getId(), node);

        UUID v2 = version(2);
        artifacts(v2, List.of(line("COL-NEW", "IfcColumn", "Column", "STOREY-1")), 1);
        pipeline.ingest(doneJob(v2));
        BimElement replacement = elements.findByModelIdAndGlobalId(modelId, "COL-NEW").orElseThrow();

        assertThatThrownBy(() -> elementService.merge(replacement.getId(), new MergeBimElementRequest(old.getId())))
                .as("only a retired row can be merged")
                .isInstanceOf(InvalidRequestException.class);

        BimElementDto merged = elementService.merge(old.getId(), new MergeBimElementRequest(replacement.getId()));

        assertThat(merged.spatialNodeId()).isEqualTo(node);
        assertThat(elements.findById(old.getId()).orElseThrow())
                .satisfies(e -> {
                    assertThat(e.getSpatialNodeId()).isNull();
                    assertThat(e.getMergedIntoId()).isEqualTo(replacement.getId());
                });
        assertThat(spatial.getNode(projectId, node).bimElementGuid()).isEqualTo("COL-NEW");
    }

    @Test
    void aBrokenArtifactFailsTheVersionWithTheReasonAndWritesNoElements() {
        UUID v1 = version(1);
        UUID job = doneJob(v1);
        artifacts(v1, List.of(line("WALL-1", "IfcWallStandardCase", "Wall 1", "STOREY-1")), 1);
        Artifacts.STORE.put(BimStorageLayout.elementsKey(modelId, v1),
                "{\"globalId\":\"WALL-1\",\"ifcType\":\"IfcWall\"}\n{not json\n".getBytes(StandardCharsets.UTF_8));

        pipeline.ingest(job);

        BimModelVersion version = versions.findById(v1).orElseThrow();
        assertThat(version.getStatus()).isEqualTo(BimVersionStatus.FAILED);
        assertThat(version.getError()).contains("line 2");
        assertThat(elements.findByModelId(modelId)).isEmpty();
        assertThat(jobs.findById(job).orElseThrow().getIngestedAt()).as("not read again").isNotNull();
        assertThat(modelService.get(modelId).currentVersionId()).isNull();
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
                        + "{\"globalId\":\"STOREY-1\",\"name\":\"Level 01\",\"elevation\":0.0,\"spaces\":[]},"
                        + "{\"globalId\":\"STOREY-2\",\"name\":\"Level 02\",\"elevation\":3.0,\"spaces\":[]}]}]}]}")
                        .getBytes(StandardCharsets.UTF_8));
        Artifacts.STORE.put(BimStorageLayout.metaKey(modelId, versionId),
                ("{\"ifcSchema\":\"IFC4\",\"units\":{\"length\":\"METRE\"},\"elementCount\":" + lines.size()
                        + ",\"storeyCount\":" + storeys + ",\"storeys\":[],\"coarseTile\":\"tiles/coarse.glb\","
                        + "\"workerVersion\":\"0.1.0\"}").getBytes(StandardCharsets.UTF_8));
    }

    private static String line(String globalId, String type, String name, String storey) {
        return "{\"globalId\":\"" + globalId + "\",\"ifcType\":\"" + type + "\",\"name\":\"" + name
                + "\",\"storeyGlobalId\":\"" + storey + "\",\"spaceGlobalId\":null,"
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
