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
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.configuration.JpaAuditingConfig;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.inspections.api.ElementTypeValidator;
import org.tornotron.echno_backend.modules.inspections.dtos.CreateElementTypeRequest;
import org.tornotron.echno_backend.modules.inspections.mapper.ElementTypeMapperImpl;
import org.tornotron.echno_backend.modules.inspections.service.ElementTypeService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.spatial.SpatialLevel;
import org.tornotron.echno_backend.project.spatial.SpatialNodeService;
import org.tornotron.echno_backend.project.spatial.dto.CreateSpatialNodeRequest;
import org.tornotron.echno_backend.project.spatial.dto.SpatialNodeDto;
import org.tornotron.echno_backend.project.spatial.dto.UpdateSpatialNodeRequest;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one place note 1 and this note meet: with the inspections module present, a spatial
 * node's element type must be one of the organization's active element types.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SpatialNodeService.class, ElementTypeValidator.class, ElementTypeService.class,
        ElementTypeMapperImpl.class, TenantEntityHelper.class, JpaAuditingConfig.class})
class SpatialElementTypeHookIT extends AbstractIntegrationTest {

    @Autowired
    private SpatialNodeService spatial;

    @Autowired
    private ElementTypeService elementTypes;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private Long orgId;
    private Long projectId;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        inCommittedTx(() -> {
            Organization org = new Organization();
            org.setOrganizationName("Hook Org");
            org.setOrganizationAddress("Hook Org address");
            org.setOrganizationEmail("hookorg@example.test");
            org.setOrganizationPhone("0000000000");
            entityManager.persist(org);
            Project project = new Project();
            project.setProjectName("Tower H");
            project.setOrganization(org);
            entityManager.persist(project);
            entityManager.flush();
            orgId = org.getId();
            projectId = project.getId();
        });
        TenantContext.setCurrentOrgId(orgId);
        entityManager.unwrap(Session.class).enableFilter("orgFilter").setParameter("organizationId", orgId);
    }

    @AfterEach
    void clearTenantState() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
        TenantContext.clear();
    }

    @AfterTransaction
    void removeCommittedRows() {
        if (orgId == null) {
            return;
        }
        inCommittedTx(() -> {
            for (String sql : new String[]{
                    "DELETE FROM project_spatial_node WHERE organization_id = :o",
                    "DELETE FROM project WHERE organization_id = :o",
                    "DELETE FROM org_element_types WHERE organization_id = :o",
                    "DELETE FROM organization WHERE id = :o"}) {
                entityManager.createNativeQuery(sql).setParameter("o", orgId).executeUpdate();
            }
        });
    }

    @Test
    void anElementNode_mustCarryAnActiveElementTypeOfTheOrganization() {
        UUID zone = zone();

        SpatialNodeDto column = spatial.create(projectId, element(zone, "C1", "column"));
        assertThat(column.elementType()).isEqualTo("column");
        // no element type at all is still allowed
        assertThat(spatial.create(projectId, element(zone, "X1", null)).elementType()).isNull();

        assertThatThrownBy(() -> spatial.create(projectId, element(zone, "H1", "hologram")))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("hologram");
        assertThatThrownBy(() -> spatial.update(projectId, column.id(),
                new UpdateSpatialNodeRequest(null, null, null, null, "hologram", null, null)))
                .isInstanceOf(InvalidRequestException.class);

        // an org-defined type is accepted as soon as it exists
        elementTypes.create(new CreateElementTypeRequest("precast-panel", "Precast panel", "structure", null, null));
        assertThat(spatial.create(projectId, element(zone, "P1", "precast-panel")).elementType())
                .isEqualTo("precast-panel");
    }

    private UUID zone() {
        SpatialNodeDto building = spatial.create(projectId, new CreateSpatialNodeRequest(null,
                SpatialLevel.BUILDING, "B1", "Block 1", null, null, null, null, null));
        SpatialNodeDto floor = spatial.create(projectId, new CreateSpatialNodeRequest(building.id(),
                SpatialLevel.FLOOR, "L01", "Level 1", null, 1, null, null, null));
        return spatial.create(projectId, new CreateSpatialNodeRequest(floor.id(),
                SpatialLevel.ZONE, "Z1", "East", null, null, null, null, null)).id();
    }

    private static CreateSpatialNodeRequest element(UUID parent, String code, String elementType) {
        return new CreateSpatialNodeRequest(parent, SpatialLevel.ELEMENT, code, "Element " + code,
                null, null, elementType, null, null);
    }

    private void inCommittedTx(Runnable work) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.executeWithoutResult(status -> work.run());
    }
}
