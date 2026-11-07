package org.tornotron.echno_backend.modules.access.matrix.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.access.matrix.catalog.AccessCatalog.Area;

/**
 * The catalog is the one list of areas: ids are unique, every section is one of the twelve, and
 * named actions sit on their area only.
 */
class AccessCatalogTest {

    @Test
    void areaIdsAreUniqueAndEverySectionIsOneOfTheTwelve() {
        Set<String> ids = AccessCatalog.areas().stream().map(Area::id).collect(Collectors.toSet());

        assertThat(ids).hasSize(AccessCatalog.areas().size());
        assertThat(AccessCatalog.SECTIONS).hasSize(12);
        assertThat(AccessCatalog.areas()).extracting(Area::section).isSubsetOf(AccessCatalog.SECTIONS);
    }

    @Test
    void namedActionsBelongToTheirAreaOnly() {
        assertThat(AccessCatalog.areas()).filteredOn(area -> area.actions().contains("certify"))
                .extracting(Area::id)
                .containsExactly("progress-bills");
    }
}
