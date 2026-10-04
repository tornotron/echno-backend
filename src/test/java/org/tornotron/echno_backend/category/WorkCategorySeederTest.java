package org.tornotron.echno_backend.category;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.category.WorkCategorySeeder.StandardCategory;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorkCategorySeederTest {

    @Test
    void standardListHasTwentyEightDistinctEntriesThatPassTheCreateRules() {
        Set<String> normalized = new HashSet<>();
        for (StandardCategory standard : WorkCategorySeeder.STANDARD_CATEGORIES) {
            // A seeded row should pass the same checks a category created from the form does.
            assertThat(standard.name()).isNotBlank();
            assertThat(standard.name().length()).isLessThanOrEqualTo(255);
            assertThat(standard.description()).isNotBlank();
            assertThat(standard.description().length()).isLessThanOrEqualTo(255);
            assertThat(normalized.add(CategoryNormalizer.normalize(standard.name())))
                    .as("duplicate normalized name for %s", standard.name())
                    .isTrue();
        }
        assertThat(normalized).hasSize(28);
    }

    @Test
    void standardSubcategoriesSitUnderTheStandardCategoriesInOrderAndPassTheColumnLimits() {
        assertThat(StandardWorkSubcategories.ALL)
                .extracting(StandardWorkSubcategories.CategorySubcategories::category)
                .containsExactlyElementsOf(WorkCategorySeeder.STANDARD_CATEGORIES.stream()
                        .map(StandardCategory::name).toList());
        int total = 0;
        for (StandardWorkSubcategories.CategorySubcategories group : StandardWorkSubcategories.ALL) {
            Set<String> normalized = new HashSet<>();
            for (StandardWorkSubcategories.StandardSubcategory sub : group.subcategories()) {
                assertThat(sub.name()).isNotBlank().hasSizeLessThanOrEqualTo(255);
                assertThat(sub.description()).isNotBlank().hasSizeLessThanOrEqualTo(500);
                assertThat(normalized.add(CategoryNormalizer.normalize(sub.name())))
                        .as("duplicate normalized sub-category %s under %s", sub.name(), group.category())
                        .isTrue();
                total++;
            }
        }
        assertThat(total).isEqualTo(215);
    }
}
