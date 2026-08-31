package org.tornotron.echno_backend.category;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.tornotron.echno_backend.category.dto.CategoryCreationDto;
import org.tornotron.echno_backend.category.mapper.CategoryMapperImpl;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Integration tests for the scope of a category name against a real CockroachDB, so the schema's
 * own constraints are part of what is under test (issue #614).
 *
 * <p>The category table carried two constraints that disagreed: {@code uk_category_name_org}
 * scoped the raw name to the organization, while {@code uq_category_normalized_name} reserved
 * the normalized form across every organization at once. The service check agreed with the
 * global one, so the first tenant to create a "Civil Works" category took the name away from
 * everyone else, and the refusal named a category the caller cannot see.
 *
 * <p>Migration 076 makes the normalized name per organization too, and the service check follows
 * it. A clash within one tenant is still refused, including one that differs only in the case,
 * punctuation or spacing the normalizer folds away.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CategoryService.class, CategoryMapperImpl.class, TenantEntityHelper.class})
class CategoryUniquenessIT extends AbstractIntegrationTest {

    private static final String SHARED_NAME = "Civil Works";

    @Autowired
    private CategoryService categoryService;

    @PersistenceContext
    private EntityManager entityManager;

    private Long firstOrgId;
    private Long secondOrgId;

    @BeforeEach
    void seed() {
        firstOrgId = persistOrganization("first");
        secondOrgId = persistOrganization("second");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private Long persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName("Category Uniqueness Org " + name);
        org.setOrganizationAddress("addr");
        org.setOrganizationEmail("category-uniqueness-" + name + "@example.test");
        org.setOrganizationPhone("0000000000");
        entityManager.persist(org);
        entityManager.flush();
        return org.getId();
    }

    private void createAs(Long orgId, String name) {
        TenantContext.setCurrentOrgId(orgId);
        CategoryCreationDto dto = new CategoryCreationDto();
        dto.setName(name);
        dto.setDescription(name + " description");
        categoryService.addCategory(dto);
        entityManager.flush();
    }

    @Test
    void sameNameInAnotherOrganization_isAccepted() {
        createAs(firstOrgId, SHARED_NAME);

        assertThatNoException().isThrownBy(() -> createAs(secondOrgId, SHARED_NAME));
    }

    @Test
    void aNameAnotherOrganizationHoldsOnlyAfterNormalizing_isAccepted() {
        createAs(firstOrgId, "Steel & Rebar");

        assertThatNoException().isThrownBy(() -> createAs(secondOrgId, "steel and rebar"));
    }

    @Test
    void sameNameTwiceInOneOrganization_isRejected() {
        createAs(firstOrgId, SHARED_NAME);

        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                createAs(firstOrgId, SHARED_NAME));
    }

    @Test
    void aNameThatOnlyNormalizesToAnExistingOneInTheSameOrganization_isRejected() {
        createAs(firstOrgId, "Steel & Rebar");

        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                createAs(firstOrgId, "  steel and   rebar "));
    }
}
