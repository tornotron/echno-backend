package org.tornotron.echno_backend.common.multitenancy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.tornotron.echno_backend.category.Category;
import org.tornotron.echno_backend.category.CategoryRepository;
import org.tornotron.echno_backend.common.exception.TenantAccessDeniedException;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that tenant isolation is fail-closed for reads. A category belongs to
 * organization B; loading it by primary key (which the Hibernate org filter never
 * covers) is rejected when the request is scoped to organization A, allowed for
 * organization B, and allowed when the tenant filter is explicitly bypassed.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TenantIsolationListenerRegistrar.class)
class TenantIsolationIT extends AbstractIntegrationTest {

    @Autowired
    private CategoryRepository categoryRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private Long orgAId;
    private Long orgBId;
    private Long categoryId;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        Organization orgA = persistOrganization("Org A");
        Organization orgB = persistOrganization("Org B");

        Category category = new Category();
        category.setName("Concrete");
        category.setOrganization(orgB);
        entityManager.persist(category);

        entityManager.flush();
        orgAId = orgA.getId();
        orgBId = orgB.getId();
        categoryId = category.getId();
        // Detach so findById reloads from the database and triggers the load listener.
        entityManager.clear();
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void findById_forTheOwningOrganization_returnsTheEntity() {
        TenantContext.setCurrentOrgId(orgBId);

        assertThat(categoryRepository.findById(categoryId)).isPresent();
    }

    @Test
    void findById_forAnotherOrganization_isRejected() {
        TenantContext.setCurrentOrgId(orgAId);

        assertThatThrownBy(() -> categoryRepository.findById(categoryId))
                .isInstanceOf(TenantAccessDeniedException.class);
    }

    @Test
    void findById_forAnotherOrganization_whenBypassed_returnsTheEntity() {
        TenantContext.setCurrentOrgId(orgAId);
        TenantContext.setBypass(true);
        try {
            assertThat(categoryRepository.findById(categoryId)).isPresent();
        } finally {
            TenantContext.setBypass(false);
        }
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
