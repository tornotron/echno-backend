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
import org.tornotron.echno_backend.organization.OrganizationRepository;
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

    @Autowired
    private OrganizationRepository organizationRepository;

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

    @Test
    void findById_onTheTenantRootItself_isNotCoveredByEitherMechanism() {
        // Organization is the one entity neither defence reaches, and necessarily so: it is
        // the tenant root, so it implements no TenantScopedEntity, carries no orgFilter, and
        // the load listener returns on its first line for it. Another organization therefore
        // loads by id under any tenant, as this proves against a real database.
        //
        // That is not a defect to fix in the mechanism, since the root cannot scope itself.
        // It is the reason an endpoint that takes an organization id from the caller and
        // resolves it here has to establish entitlement in its own guard, having nothing
        // underneath to fall back on. #687 was such an endpoint.
        TenantContext.setCurrentOrgId(orgAId);

        assertThat(organizationRepository.findById(orgBId)).isPresent();
    }

    @Test
    void aStrangerIsRefusedOnAReadThroughADerivedQuery() {
        // The stranger case stated as such: a caller scoped to organization A has no membership
        // of, no role in and no relationship at all to organization B. #717 removed 36 guards
        // whose role clause was unreachable and left the tenant resolved from membership alone,
        // so this is the boundary that has to keep holding for the removal to be safe.
        //
        // findById is covered above. This goes through a derived query instead, because the two
        // reach the listener by different routes: the org filter never covers a primary-key
        // load, and it is not enabled here either, so what refuses the row is the load listener
        // on materialization in both cases and it is worth showing on a query as well.
        TenantContext.setCurrentOrgId(orgAId);

        assertThatThrownBy(() -> categoryRepository.findCategoryByName("Concrete"))
                .isInstanceOf(TenantAccessDeniedException.class);
    }

    @Test
    void aStrangerIsRefusedOnAWrite() {
        // A write has to load before it can persist: saving a detached instance issues a select
        // first, which is the load the listener sees. So the refusal arrives before any row is
        // touched rather than after, which is what makes this a boundary rather than an audit.
        Category detached = new Category();
        detached.setId(categoryId);
        detached.setName("Renamed by a stranger");

        TenantContext.setCurrentOrgId(orgAId);

        assertThatThrownBy(() -> categoryRepository.saveAndFlush(detached))
                .isInstanceOf(TenantAccessDeniedException.class);
    }

    @Test
    void theStrangersWriteLeavesTheRowUnchanged() {
        // The other half of the write case, asserted separately: a refused write must not have
        // landed. Read back under the owning tenant, and compare one property rather than the
        // entity, because Organization is a Lombok @Data entity whose toString walks its lazy
        // employees, so letting an assertion describe one raises an exception of its own from
        // inside the failure message.
        Category detached = new Category();
        detached.setId(categoryId);
        detached.setName("Renamed by a stranger");

        TenantContext.setCurrentOrgId(orgAId);
        assertThatThrownBy(() -> categoryRepository.saveAndFlush(detached))
                .isInstanceOf(TenantAccessDeniedException.class);

        entityManager.clear();
        TenantContext.setCurrentOrgId(orgBId);

        assertThat(categoryRepository.findById(categoryId).orElseThrow().getName())
                .isEqualTo("Concrete");
    }

    @Test
    void aStrangerIsRefusedThroughAnHqlJoin() {
        // A join is the interesting shape, because the tenant condition sits on the joined side
        // and a reader can convince themselves the join itself scopes the query. It does not:
        // the org filter is not enabled here, so the query happily selects organization B's row
        // and the refusal comes when the entity is materialized. Naming organization B in the
        // where clause is the point, since that is what a caller who knows the id would write.
        TenantContext.setCurrentOrgId(orgAId);

        assertThatThrownBy(() -> entityManager.createQuery(
                        "select c from Category c join c.organization o where o.id = :orgId", Category.class)
                .setParameter("orgId", orgBId)
                .getResultList())
                .isInstanceOf(TenantAccessDeniedException.class);
    }

    @Test
    void theOwningTenantStillPassesThroughTheSameHqlJoin() {
        // The join is refused for the stranger and not for everyone: without this the test above
        // would pass just as well against a query that was broken outright.
        TenantContext.setCurrentOrgId(orgBId);

        assertThat(entityManager.createQuery(
                        "select c from Category c join c.organization o where o.id = :orgId", Category.class)
                .setParameter("orgId", orgBId)
                .getResultList())
                .hasSize(1);
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