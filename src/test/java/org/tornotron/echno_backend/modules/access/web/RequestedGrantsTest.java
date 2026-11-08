package org.tornotron.echno_backend.modules.access.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.web.RequestedGrants.Grant;
import org.tornotron.echno_backend.modules.access.web.dto.AccessGrantRequest;

class RequestedGrantsTest {

    private static final Set<String> CATALOG = Set.of(
            "wbs:read", "wbs:edit", "progress-bills:read", "progress-bills:certify");
    private static final Predicate<AccessPermission> DECLARED = permission -> CATALOG.contains(permission.key());

    @Test
    void everyActionBringsReadWithTheSameScope() {
        assertThat(RequestedGrants.parse(List.of(new AccessGrantRequest("wbs:edit", "ASSIGNED_PROJECTS")), DECLARED))
                .containsExactly(
                        new Grant(AccessPermission.of("wbs:read"), AccessScope.ASSIGNED_PROJECTS),
                        new Grant(AccessPermission.of("wbs:edit"), AccessScope.ASSIGNED_PROJECTS));
    }

    @Test
    void whatWasAskedForIsKeptApartFromTheReadsItBrings() {
        assertThat(RequestedGrants.asked(List.of(new AccessGrantRequest("wbs:edit", "ALL")), DECLARED))
                .containsExactly(new Grant(AccessPermission.of("wbs:edit"), AccessScope.ALL));
    }

    @Test
    void repeatsAreDropped() {
        assertThat(RequestedGrants.parse(List.of(
                new AccessGrantRequest("wbs:read", "ALL"),
                new AccessGrantRequest("wbs:edit", "ALL"),
                new AccessGrantRequest("wbs:edit", "ALL")), DECLARED)).hasSize(2);
    }

    @Test
    void anAreasNamedActionsAreAccepted() {
        assertThat(RequestedGrants.parse(List.of(new AccessGrantRequest("progress-bills:certify", "ALL")), DECLARED))
                .contains(new Grant(AccessPermission.of("progress-bills:certify"), AccessScope.ALL));
    }

    @Test
    void aPermissionOutsideTheCatalogIsRefused() {
        assertThatThrownBy(() -> RequestedGrants.parse(List.of(new AccessGrantRequest("wbs:certify", "ALL")), DECLARED))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("wbs:certify");
        assertThatThrownBy(() -> RequestedGrants.parse(List.of(new AccessGrantRequest("payroll:read", "ALL")), DECLARED))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void aMalformedPermissionOrScopeIsRefused() {
        assertThatThrownBy(() -> RequestedGrants.parse(List.of(new AccessGrantRequest("wbs", "ALL")), DECLARED))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> RequestedGrants.parse(List.of(new AccessGrantRequest("wbs:read", "MINE")), DECLARED))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("MINE");
    }
}
