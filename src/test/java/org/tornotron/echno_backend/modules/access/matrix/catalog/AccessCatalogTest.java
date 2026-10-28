package org.tornotron.echno_backend.modules.access.matrix.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.matrix.catalog.AccessCatalog.Area;
import org.tornotron.echno_backend.modules.access.matrix.seed.StandardRoles;

/**
 * The catalog is the one list of areas: ids are unique, every section is one of the twelve, every
 * permission the starting roles grant is one the catalog offers, and named actions sit on their
 * area only.
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
    void everyPermissionTheStartingRolesGrantIsInTheCatalog() {
        StandardRoles.all().forEach(role -> role.grants().forEach(grant ->
                assertThat(AccessCatalog.offers(grant.permission()))
                        .as("%s grants %s", role.slug(), grant.permission())
                        .isTrue()));
    }

    @Test
    void namedActionsBelongToTheirAreaOnly() {
        assertThat(AccessCatalog.offers(AccessPermission.of("progress-bills:certify"))).isTrue();
        assertThat(AccessCatalog.offers(AccessPermission.of("finance:certify"))).isFalse();
        assertThat(AccessCatalog.offers(AccessPermission.of("harbour:view"))).isFalse();
    }
}
