package org.tornotron.echno_backend.common.multitenancy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.tornotron.echno_backend.common.exception.UnscopedTenantAccessException;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that tenant isolation is fail-closed for reads, through the real Hibernate event
 * pipeline against a real database rather than a hand-built listener. A category belongs to
 * organization B; loading it by primary key (which the Hibernate org filter never covers) is
 * rejected when the request is scoped to organization A, allowed for organization B, and
 * allowed when the tenant filter is explicitly bypassed.
 *
 * <p>Since #507 it is also rejected when nothing declared a tenant scope at all, and allowed
 * again once the caller declares itself unscoped. That is the case that used to return the row
 * with no check of any kind, so it is the one worth running against a database: the early return
 * it replaces sat in the listener, but what it let through was a real cross-tenant read.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TenantIsolationListenerRegistrar.class, UnscopedAccessGuard.class, SimpleMeterRegistry.class})
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

    @Test
    void findById_withNoTenantScopeDeclaredAtAll_isRefused() {
        // No organization id, no bypass, no @WithoutTenant. This is the state a background job
        // that forgot its tenant is in, and until #507 it read every organization's rows.
        assertThatThrownBy(() -> categoryRepository.findById(categoryId))
                .isInstanceOf(UnscopedTenantAccessException.class)
                .hasMessageContaining("Category");
    }

    @Test
    void findById_declaredUnscoped_returnsTheEntity() {
        TenantContext.declareUnscoped("a startup path that belongs to no organization");

        assertThat(categoryRepository.findById(categoryId)).isPresent();
    }

    @Test
    void anUnscopedDeclarationDoesNotWeakenAnActiveTenant() {
        // The declaration only answers the missing-scope question. With an organization in
        // force the cross-tenant check still runs, which is what keeps @WithoutTenant safe on
        // shared service code that a tenant request may also call.
        TenantContext.setCurrentOrgId(orgAId);
        TenantContext.declareUnscoped("a shared helper that usually has no tenant");

        assertThatThrownBy(() -> categoryRepository.findById(categoryId))
                .isInstanceOf(TenantAccessDeniedException.class);
        assertThatCode(() -> {
            TenantContext.setCurrentOrgId(orgBId);
            assertThat(categoryRepository.findById(categoryId)).isPresent();
        }).doesNotThrowAnyException();
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
