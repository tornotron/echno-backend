package org.tornotron.echno_backend.modules.access.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.matrix.assignment.RoleAssignmentITSupport;
import org.tornotron.echno_backend.modules.access.matrix.catalog.AccessCatalogReader;
import org.tornotron.echno_backend.modules.access.matrix.catalog.AccessCatalogReader.AreaEntry;
import org.tornotron.echno_backend.modules.access.web.dto.AccessGrantRequest;

/**
 * A grant may cover fewer than all records only on an area that filters its queries by scope:
 * anywhere else it would quietly mean all records. Driven from the catalog and the scope-aware
 * registry, so an area that registers is accepted with no change here.
 */
class ScopeRefusalIT extends RoleAssignmentITSupport {

    @Autowired
    private AccessCatalogReader catalog;

    @Test
    void everyAreaThatDoesNotFilterByScopeRefusesANarrowerScope() {
        administrator("scope-admin");
        UUID clerk = writer.createRole("Clerk", null, null).id();
        List<AreaEntry> unaware = catalog.areas().stream().filter(area -> !area.scopeAware()).toList();

        assertThat(unaware).isNotEmpty();
        for (AreaEntry area : unaware) {
            for (AccessScope scope : List.of(AccessScope.ASSIGNED_PROJECTS, AccessScope.RELATED, AccessScope.OWN)) {
                assertThatThrownBy(() -> writer.replaceRoleGrants(clerk,
                        List.of(new AccessGrantRequest(area.id() + ":read", scope.name()))))
                        .as("%s with %s", area.id(), scope)
                        .isInstanceOf(InvalidRequestException.class)
                        .hasMessageContaining("does not filter its records by scope");
            }
        }
        assertThat(grantsOf(clerk)).isEmpty();
    }

    @Test
    void anAreaThatRegisteredTakesAnyScope() {
        administrator("aware-admin");
        UUID clerk = writer.createRole("Clerk", null, null).id();
        assertThat(catalog.areas()).filteredOn(AreaEntry::scopeAware).extracting(AreaEntry::id)
                .containsExactly(SCOPE_AWARE_AREA);

        writer.replaceRoleGrants(clerk, List.of(new AccessGrantRequest(SCOPE_AWARE_AREA + ":edit", "OWN"),
                new AccessGrantRequest(SCOPE_AWARE_AREA + ":read", "ASSIGNED_PROJECTS")));

        assertThat(grantsOf(clerk)).containsExactly(
                "assets:edit OWN", "assets:read ASSIGNED_PROJECTS", "assets:read OWN");
    }
}
