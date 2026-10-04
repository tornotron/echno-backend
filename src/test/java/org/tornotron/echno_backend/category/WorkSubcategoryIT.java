package org.tornotron.echno_backend.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.tornotron.echno_backend.category.dto.WorkSubcategoryDto;
import org.tornotron.echno_backend.category.mapper.CategoryMapperImpl;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * Work sub-categories on the real migration: the seeder a new organization runs, the backfill
 * changeset for the organizations that existed before it, the case-insensitive dedupe both rely
 * on, and that one organization cannot list another's.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CategoryService.class, CategoryMapperImpl.class, TenantEntityHelper.class, WorkCategorySeeder.class})
class WorkSubcategoryIT extends AbstractIntegrationTest {

    private static final int STANDARD_TOTAL = 215;

    @Autowired
    private WorkCategorySeeder seeder;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager entityManager;

    private Long orgAId;
    private Long orgBId;

    @BeforeEach
    void seedOrganizations() {
        orgAId = persistOrganization("a");
        orgBId = persistOrganization("b");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void aNewOrganizationGetsEveryStandardSubcategoryOnceAndARerunAddsNone() {
        TenantContext.setCurrentOrgId(orgAId);
        seeder.seedDefaults();
        entityManager.flush();
        assertThat(subcategoryCount(orgAId)).isEqualTo(STANDARD_TOTAL);

        seeder.seedDefaults();
        entityManager.flush();
        assertThat(subcategoryCount(orgAId)).isEqualTo(STANDARD_TOTAL);
        assertThat(subcategoryCount(orgBId)).isZero();

        Long earthwork = categoryId(orgAId, "earthwork");
        List<WorkSubcategoryDto> listed = categoryService.getSubcategories(earthwork);
        List<String> expected = standard("Earthwork").stream()
                .map(StandardWorkSubcategories.StandardSubcategory::name).toList();
        assertThat(listed).extracting(WorkSubcategoryDto::name).containsExactlyElementsOf(expected);
        assertThat(listed).allSatisfy(dto -> assertThat(dto.categoryId()).isEqualTo(earthwork));
    }

    @Test
    void aSubcategoryTheCategoryAlreadyHoldsInAnotherCaseIsKeptAndNotDuplicated() {
        TenantContext.setCurrentOrgId(orgAId);
        seeder.seedDefaults();
        entityManager.flush();
        Long earthwork = categoryId(orgAId, "earthwork");
        jdbc.update("DELETE FROM work_subcategory WHERE category_id = ? AND normalized_name = 'excavation'", earthwork);
        jdbc.update("INSERT INTO work_subcategory (organization_id, category_id, name, normalized_name, sort_order) "
                + "VALUES (?, ?, 'EXCAVATION ', 'excavation', 99)", orgAId, earthwork);
        entityManager.clear();

        seeder.seedDefaults();
        entityManager.flush();

        assertThat(categoryService.getSubcategories(earthwork)).extracting(WorkSubcategoryDto::name)
                .hasSize(standard("Earthwork").size())
                .contains("EXCAVATION ")
                .doesNotContain("Excavation");
    }

    @Test
    void theBackfillAttachesToExistingCategoriesByNameIgnoringCaseAndIsIdempotent() throws IOException {
        // A legacy row from before normalized_name, in another case, and a current one.
        jdbc.update("INSERT INTO category (category_name, normalized_name, category_description, organization_id) "
                + "VALUES ('EARTHWORK', NULL, 'legacy', ?)", orgAId);
        jdbc.update("INSERT INTO category (category_name, normalized_name, category_description, organization_id) "
                + "VALUES ('Foundation Works', 'foundation works', 'current', ?)", orgAId);
        jdbc.update("INSERT INTO category (category_name, normalized_name, category_description, organization_id) "
                + "VALUES ('Own Category', 'own category', 'not standard', ?)", orgBId);

        String backfill = backfillSql();
        jdbc.execute(backfill);
        int expected = standard("Earthwork").size() + standard("Foundation Works").size();
        assertThat(subcategoryCount(orgAId)).isEqualTo(expected);
        assertThat(subcategoryCount(orgBId)).isZero();

        jdbc.execute(backfill);
        assertThat(subcategoryCount(orgAId)).isEqualTo(expected);
    }

    @Test
    void anotherOrganizationsCategoryAnswersNotFound() {
        TenantContext.setCurrentOrgId(orgAId);
        seeder.seedDefaults();
        entityManager.flush();
        Long earthworkOfA = categoryId(orgAId, "earthwork");

        TenantContext.setCurrentOrgId(orgBId);
        assertThatThrownBy(() -> categoryService.getSubcategories(earthworkOfA))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------- helpers

    private static List<StandardWorkSubcategories.StandardSubcategory> standard(String category) {
        return StandardWorkSubcategories.ALL.stream()
                .filter(group -> group.category().equals(category))
                .findFirst()
                .orElseThrow()
                .subcategories();
    }

    private Long categoryId(Long orgId, String normalizedName) {
        return categoryRepository.findFirstByNormalizedNameAndOrganization_IdOrderByIdAsc(normalizedName, orgId)
                .orElseThrow()
                .getId();
    }

    private int subcategoryCount(Long orgId) {
        entityManager.flush();
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM work_subcategory WHERE organization_id = ?", Integer.class, orgId);
        return count == null ? 0 : count;
    }

    private String backfillSql() throws IOException {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("db/changelog/v4.0/130-work-subcategories.xml")) {
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher cdata = Pattern.compile("<!\\[CDATA\\[(.*?)]]>", Pattern.DOTALL).matcher(xml);
            assertThat(cdata.find()).as("the backfill SQL in changeset 130").isTrue();
            return cdata.group(1);
        }
    }

    private Long persistOrganization(String suffix) {
        Organization org = new Organization();
        org.setOrganizationName("Work Subcategory Org " + suffix);
        org.setOrganizationAddress("addr");
        org.setOrganizationEmail("work-subcategory-" + suffix + "@example.test");
        org.setOrganizationPhone("0000000000");
        entityManager.persist(org);
        entityManager.flush();
        return org.getId();
    }
}
