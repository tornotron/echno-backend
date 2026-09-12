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
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.inspections.dtos.CreateElementTypeRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.OrgElementTypeDto;
import org.tornotron.echno_backend.modules.inspections.dtos.ElementTypeCatalogueDto;
import org.tornotron.echno_backend.modules.inspections.dtos.UpdateElementTypeRequest;
import org.tornotron.echno_backend.modules.inspections.mapper.ElementTypeMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.ElementTypeMapperImpl;
import org.tornotron.echno_backend.modules.inspections.repositories.OrgElementTypeRepository;
import org.tornotron.echno_backend.modules.inspections.service.ElementTypeService;
import org.tornotron.echno_backend.modules.inspections.service.ElementTypeService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The element type catalogue and its per-organization copy: the lazy copy and its
 * idempotency, the validation hook a spatial node's element type is checked against,
 * org-defined types and tenant isolation.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ElementTypeService.class, ElementTypeMapperImpl.class,
        ElementTypeService.class, ElementTypeMapperImpl.class, TenantEntityHelper.class})
class ElementTypeServiceIT extends AbstractIntegrationTest {

    @Autowired
    private ElementTypeService service;

    @Autowired
    private OrgElementTypeRepository orgRepo;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private Long orgAId;
    private Long orgBId;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        inCommittedTx(() -> {
            Organization orgA = persistOrganization("Element Org A");
            Organization orgB = persistOrganization("Element Org B");
            entityManager.flush();
            orgAId = orgA.getId();
            orgBId = orgB.getId();
        });
        TenantContext.setCurrentOrgId(orgAId);
    }

    @AfterEach
    void clearTenantState() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
        TenantContext.clear();
    }

    @AfterTransaction
    void removeCommittedRows() {
        if (orgAId == null && orgBId == null) {
            return;
        }
        inCommittedTx(() -> {
            deleteForOrgs("DELETE FROM org_element_types WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM org_element_types WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM organization WHERE id IN (:a,:b)");
        });
    }

    @Test
    void catalogue_holdsTheSeventeenSeededTypesInFiveGroups() {
        List<ElementTypeCatalogueDto> catalogue = service.listCatalogue();

        assertThat(catalogue).hasSize(17);
        assertThat(catalogue).extracting(ElementTypeCatalogueDto::code)
                .contains("column", "beam", "slab", "wall", "staircase", "footing", "lintel", "door",
                        "window", "ceiling", "floor-finish", "wall-finish", "pipe-run", "duct",
                        "cable-tray", "fire-door", "sprinkler-branch");
        assertThat(catalogue).extracting(ElementTypeCatalogueDto::groupCode)
                .containsOnly("structure", "openings", "finishes", "services", "fire");
    }

    @Test
    void firstRead_copiesTheCatalogueIntoTheOrganizationOnceOnly() {
        assertThat(orgRepo.findByOrganizationIdOrderBySortOrderAscNameAsc(orgAId)).isEmpty();

        List<OrgElementTypeDto> first = service.listOrgElementTypes(false);
        assertThat(first).hasSize(17);
        assertThat(first).allSatisfy(t -> assertThat(t.catalogueCode()).isEqualTo(t.code()));

        assertThat(service.ensureOrgElementTypes(entityManager.find(Organization.class, orgAId))).isZero();
        assertThat(service.listOrgElementTypes(false)).hasSize(17);
        assertThat(orgRepo.findByOrganizationIdOrderBySortOrderAscNameAsc(orgBId)).isEmpty();
    }

    @Test
    void codes_validateAgainstTheOrganizationsListActiveOrRetired() {
        assertThat(service.isKnownCode("column")).isTrue();
        assertThat(service.isActiveCode("Column")).isTrue();
        assertThat(service.isKnownCode("hologram")).isFalse();
        assertThat(service.isKnownCode(null)).isFalse();

        OrgElementTypeDto column = service.listOrgElementTypes(false).stream()
                .filter(t -> t.code().equals("column")).findFirst().orElseThrow();
        service.update(column.id(), new UpdateElementTypeRequest(null, null, null, null, false));

        // retired: gone from the picker, no longer accepted for a new element, still known
        assertThat(service.listOrgElementTypes(false)).extracting(OrgElementTypeDto::code).doesNotContain("column");
        assertThat(service.isActiveCode("column")).isFalse();
        assertThat(service.isKnownCode("column")).isTrue();
    }

    @Test
    void create_addsAnOrgDefinedTypeThatCannotBeDuplicated() {
        OrgElementTypeDto created = service.create(new CreateElementTypeRequest(
                "precast-panel", "Precast panel", "structure", null, null));

        assertThat(created.catalogueCode()).isNull();
        assertThat(created.sortOrder()).isEqualTo(1000);
        assertThat(service.isActiveCode("precast-panel")).isTrue();
        assertThat(service.listOrgElementTypes(false)).hasSize(18);

        assertThatThrownBy(() -> service.create(new CreateElementTypeRequest(
                "precast-panel", "Again", "structure", null, null)))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void elementTypes_areScopedToTheOwningTenant() {
        OrgElementTypeDto custom = service.create(new CreateElementTypeRequest(
                "curtain-wall", "Curtain wall", "openings", null, null));
        entityManager.flush();
        entityManager.clear();

        TenantContext.setCurrentOrgId(orgBId);
        enableOrgFilter(orgBId);
        assertThat(service.listOrgElementTypes(true)).extracting(OrgElementTypeDto::code).doesNotContain("curtain-wall");
        assertThat(service.isKnownCode("curtain-wall")).isFalse();
        assertThatThrownBy(() -> service.update(custom.id(), new UpdateElementTypeRequest("Stolen", null, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        disableOrgFilter();

        TenantContext.setCurrentOrgId(orgAId);
        enableOrgFilter(orgAId);
        assertThat(service.isKnownCode("curtain-wall")).isTrue();
        disableOrgFilter();
    }

    private void enableOrgFilter(Long orgId) {
        entityManager.unwrap(Session.class)
                .enableFilter("orgFilter")
                .setParameter("organizationId", orgId);
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
