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
}
