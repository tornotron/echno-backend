package org.tornotron.echno_backend.project.spatial;

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
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.configuration.JpaAuditingConfig;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.project.spatial.dto.CreateSpatialNodeRequest;
import org.tornotron.echno_backend.project.spatial.dto.SpatialImportResult;
import org.tornotron.echno_backend.project.spatial.dto.SpatialImportRow;
import org.tornotron.echno_backend.project.spatial.dto.SpatialNodeDto;
import org.tornotron.echno_backend.project.spatial.dto.SpatialPathSegment;
import org.tornotron.echno_backend.project.spatial.dto.SpatialTreeNodeDto;
import org.tornotron.echno_backend.project.spatial.dto.UpdateSpatialNodeRequest;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The site-structure rules against a real CockroachDB: the strict level chain, the
 * materialised path on create and move, archive cascading down and restore coming back up,
 * the default zone, and the org filter hiding one tenant's tree from another.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SpatialNodeService.class, TenantEntityHelper.class, JpaAuditingConfig.class})
class SpatialNodeServiceIT extends AbstractIntegrationTest {

    @Autowired
    private SpatialNodeService service;

    @Autowired
    private SpatialNodeRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private Long orgAId;
    private Long orgBId;
    private Long projectId;
    private Long otherProjectId;

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
            deleteForOrgs("DELETE FROM project_spatial_node WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM project WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM organization WHERE id IN (:a,:b)");
        });
    }

    @Test
    void createMaintainsPathDepthAndBreadcrumb() {
        SpatialNodeDto building = create(null, SpatialLevel.BUILDING, "B1", "Block 1");
        SpatialNodeDto floor = create(building.id(), SpatialLevel.FLOOR, "L03", "Level 3", 3);
        SpatialNodeDto zone = create(floor.id(), SpatialLevel.ZONE, "Z1", "East wing");
        SpatialNodeDto element = create(zone.id(), SpatialLevel.ELEMENT, "C4", "Column C4");

        assertThat(building.depth()).isZero();
        assertThat(element.depth()).isEqualTo(3);
        assertThat(floor.levelIndex()).isEqualTo(3);

        SpatialNode stored = repository.findByIdScoped(element.id()).orElseThrow();
        assertThat(stored.getPath()).isEqualTo(
                "/" + building.id() + "/" + floor.id() + "/" + zone.id() + "/" + element.id());
        assertThat(stored.getCreatedAt()).isNotNull();

        List<SpatialPathSegment> path = service.getNode(projectId, element.id()).spatialPath();
        assertThat(path).extracting(SpatialPathSegment::code).containsExactly("B1", "L03", "Z1", "C4");
        assertThat(path).extracting(SpatialPathSegment::level).containsExactly(
                SpatialLevel.BUILDING, SpatialLevel.FLOOR, SpatialLevel.ZONE, SpatialLevel.ELEMENT);

        List<SpatialTreeNodeDto> tree = service.getTree(projectId, false);
        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).children().get(0).children().get(0).children())
                .extracting(SpatialTreeNodeDto::code).containsExactly("C4");
    }

    @Test
    void levelChainIsStrict() {
        SpatialNodeDto building = create(null, SpatialLevel.BUILDING, "B1", "Block 1");
        SpatialNodeDto floor = create(building.id(), SpatialLevel.FLOOR, "L01", "Level 1");

        assertThatThrownBy(() -> create(building.id(), SpatialLevel.ZONE, "Z1", "Zone under building"))
                .isInstanceOf(SpatialNodeConflictException.class);
        assertThatThrownBy(() -> create(floor.id(), SpatialLevel.ELEMENT, "C1", "Element under floor"))
                .isInstanceOf(SpatialNodeConflictException.class);
        assertThatThrownBy(() -> create(null, SpatialLevel.FLOOR, "L02", "Orphan floor"))
                .isInstanceOf(SpatialNodeConflictException.class);
        assertThatThrownBy(() -> create(building.id(), SpatialLevel.BUILDING, "B2", "Nested building"))
                .isInstanceOf(SpatialNodeConflictException.class);
        assertThatThrownBy(() -> create(UUID.randomUUID(), SpatialLevel.FLOOR, "L09", "Unknown parent"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void siblingCodesAreUniqueAndBimGuidIsUniquePerProject() {
        SpatialNodeDto b1 = create(null, SpatialLevel.BUILDING, "B1", "Block 1");
        SpatialNodeDto b2 = create(null, SpatialLevel.BUILDING, "B2", "Block 2");
        create(b1.id(), SpatialLevel.FLOOR, "L01", "Level 1");

        assertThatThrownBy(() -> create(null, SpatialLevel.BUILDING, "B1", "Duplicate block"))
                .isInstanceOf(SpatialNodeConflictException.class);
        assertThatThrownBy(() -> create(b1.id(), SpatialLevel.FLOOR, "L01", "Duplicate floor"))
                .isInstanceOf(SpatialNodeConflictException.class);
        // the same code under another parent is fine
        SpatialNodeDto l01b = create(b2.id(), SpatialLevel.FLOOR, "L01", "Level 1");
        assertThat(l01b.code()).isEqualTo("L01");

        SpatialNodeDto z = create(l01b.id(), SpatialLevel.ZONE, "Z", "Zone");
        service.create(projectId, new CreateSpatialNodeRequest(z.id(), SpatialLevel.ELEMENT, "C1", "Column",
                null, null, "column", "1kTvXnbbzCWw8lcMd1dR4o", null));
        assertThatThrownBy(() -> service.create(projectId, new CreateSpatialNodeRequest(z.id(),
                SpatialLevel.ELEMENT, "C2", "Column", null, null, "column", "1kTvXnbbzCWw8lcMd1dR4o", null)))
                .isInstanceOf(SpatialNodeConflictException.class);

        // renaming onto a sibling's code is refused too
        assertThatThrownBy(() -> service.update(projectId, b2.id(),
                new UpdateSpatialNodeRequest("B1", null, null, null, null, null, null)))
                .isInstanceOf(SpatialNodeConflictException.class);
    }

    @Test
    void moveRewritesDescendantPathsAndRefusesBadTargets() {
        SpatialNodeDto b1 = create(null, SpatialLevel.BUILDING, "B1", "Block 1");
        SpatialNodeDto b2 = create(null, SpatialLevel.BUILDING, "B2", "Block 2");
        SpatialNodeDto floor = create(b1.id(), SpatialLevel.FLOOR, "L01", "Level 1");
        SpatialNodeDto zone = create(floor.id(), SpatialLevel.ZONE, "Z1", "Zone 1");
        SpatialNodeDto element = create(zone.id(), SpatialLevel.ELEMENT, "C1", "Column 1");

        service.move(projectId, floor.id(), b2.id());
        entityManager.flush();
        entityManager.clear();

        SpatialNode movedElement = repository.findByIdScoped(element.id()).orElseThrow();
        assertThat(movedElement.getPath()).isEqualTo(
                "/" + b2.id() + "/" + floor.id() + "/" + zone.id() + "/" + element.id());
        assertThat(movedElement.getDepth()).isEqualTo(3);
        assertThat(repository.findByIdScoped(floor.id()).orElseThrow().getParentId()).isEqualTo(b2.id());
        assertThat(service.getNode(projectId, element.id()).spatialPath())
                .extracting(SpatialPathSegment::code).containsExactly("B2", "L01", "Z1", "C1");

        // wrong level, own subtree, and a building
        assertThatThrownBy(() -> service.move(projectId, floor.id(), zone.id()))
                .isInstanceOf(SpatialNodeConflictException.class);
        assertThatThrownBy(() -> service.move(projectId, zone.id(), element.id()))
                .isInstanceOf(SpatialNodeConflictException.class);
        assertThatThrownBy(() -> service.move(projectId, b1.id(), b2.id()))
                .isInstanceOf(SpatialNodeConflictException.class);
    }

    @Test
    void archiveCascadesAndRestoreBringsTheSubtreeBack() {
        SpatialNodeDto b1 = create(null, SpatialLevel.BUILDING, "B1", "Block 1");
        SpatialNodeDto floor = create(b1.id(), SpatialLevel.FLOOR, "L01", "Level 1");
        SpatialNodeDto zone = create(floor.id(), SpatialLevel.ZONE, "Z1", "Zone 1");
        SpatialNodeDto element = create(zone.id(), SpatialLevel.ELEMENT, "C1", "Column 1");

        service.archive(projectId, floor.id());
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findByIdScoped(element.id()).orElseThrow().isArchived()).isTrue();
        assertThat(service.getTree(projectId, false).get(0).children()).isEmpty();
        assertThat(service.getTree(projectId, true).get(0).children()).hasSize(1);
        // an existing reference still resolves; a new one is refused
        assertThat(service.pathOf(element.id())).hasSize(4);
        assertThatThrownBy(() -> service.requireUsableNode(projectId, element.id()))
                .isInstanceOf(SpatialNodeArchivedException.class);
        assertThatThrownBy(() -> create(zone.id(), SpatialLevel.ELEMENT, "C2", "Under archived zone"))
                .isInstanceOf(SpatialNodeArchivedException.class);
        // a child cannot be restored under an archived parent
        assertThatThrownBy(() -> service.restore(projectId, zone.id()))
                .isInstanceOf(SpatialNodeArchivedException.class);

        service.restore(projectId, floor.id());
        entityManager.flush();
        entityManager.clear();
        assertThat(repository.findByIdScoped(element.id()).orElseThrow().isArchived()).isFalse();
        assertThat(service.requireUsableNode(projectId, element.id()).getId()).isEqualTo(element.id());
    }

    @Test
    void ensureDefaultZoneIsIdempotentAndFloorOnly() {
        SpatialNodeDto b1 = create(null, SpatialLevel.BUILDING, "B1", "Block 1");
        SpatialNodeDto floor = create(b1.id(), SpatialLevel.FLOOR, "L01", "Level 1");

        SpatialNode zone = service.ensureDefaultZone(projectId, floor.id());
        assertThat(zone.getLevel()).isEqualTo(SpatialLevel.ZONE);
        assertThat(zone.getCode()).isEqualTo("L01");
        assertThat(zone.getPath()).isEqualTo("/" + b1.id() + "/" + floor.id() + "/" + zone.getId());
        assertThat(service.ensureDefaultZone(projectId, floor.id()).getId()).isEqualTo(zone.getId());
        assertThat(repository.findByProjectIdAndParentIdAndArchivedAtIsNull(projectId, floor.id())).hasSize(1);

        assertThatThrownBy(() -> service.ensureDefaultZone(projectId, b1.id()))
                .isInstanceOf(SpatialNodeConflictException.class);
    }

    @Test
    void nodesOfAnotherTenantOrProjectReadAsAbsent() {
        SpatialNodeDto b1 = create(null, SpatialLevel.BUILDING, "B1", "Block 1");
        entityManager.flush();
        entityManager.clear();

        // another project of the same tenant
        assertThatThrownBy(() -> service.getNode(otherProjectId, b1.id()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(service.getTree(otherProjectId, true)).isEmpty();

        // another tenant
        disableOrgFilter();
        enableOrgFilter(orgBId);
        TenantContext.setCurrentOrgId(orgBId);
        assertThat(repository.findByIdScoped(b1.id())).isEmpty();
        assertThat(service.pathOf(b1.id())).isEmpty();
        assertThat(service.subtreePathPrefix(b1.id())).isEmpty();
        assertThatThrownBy(() -> service.getNode(projectId, b1.id()))
                .isInstanceOf(ResourceNotFoundException.class);

        disableOrgFilter();
        enableOrgFilter(orgAId);
        TenantContext.setCurrentOrgId(orgAId);
        assertThat(service.subtreePathPrefix(b1.id())).contains("/" + b1.id());
    }

    @Test
    void importIsIdempotentOnTheCodePath() {
        List<SpatialImportRow> rows = List.of(
                new SpatialImportRow("B1", "L01", 1, "Z1", "C1", "column"),
                new SpatialImportRow("B1", "L01", 1, "Z1", "C2", "column"),
                new SpatialImportRow("B1", "L02", 2, null, "S1", "slab"),
                new SpatialImportRow("B2", null, null, null, null, null));

        SpatialImportResult first = service.importRows(projectId, rows);
        // B1, L01, Z1, C1 | C2 | L02, default zone, S1 | B2
        assertThat(first.created()).isEqualTo(9);
        assertThat(first.skipped()).isEqualTo(4);

        SpatialImportResult second = service.importRows(projectId, rows);
        assertThat(second.created()).isZero();
        assertThat(second.skipped()).isEqualTo(13);

        List<SpatialTreeNodeDto> tree = service.getTree(projectId, false);
        assertThat(tree).extracting(SpatialTreeNodeDto::code).containsExactly("B1", "B2");
        SpatialTreeNodeDto l02 = tree.get(0).children().get(1);
        assertThat(l02.code()).isEqualTo("L02");
        assertThat(l02.levelIndex()).isEqualTo(2);
        assertThat(l02.children()).extracting(SpatialTreeNodeDto::code).containsExactly("L02");
        assertThat(l02.children().get(0).children()).extracting(SpatialTreeNodeDto::code).containsExactly("S1");
        assertThat(l02.children().get(0).children().get(0).elementType()).isEqualTo("slab");

        assertThatThrownBy(() -> service.importRows(projectId,
                List.of(new SpatialImportRow("B1", null, null, "Z9", null, null))))
                .isInstanceOf(InvalidRequestException.class);
    }

    private SpatialNodeDto create(UUID parentId, SpatialLevel level, String code, String name) {
        return create(parentId, level, code, name, null);
    }

    private SpatialNodeDto create(UUID parentId, SpatialLevel level, String code, String name, Integer levelIndex) {
        return service.create(projectId, new CreateSpatialNodeRequest(parentId, level, code, name,
                null, levelIndex, null, null, null));
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
