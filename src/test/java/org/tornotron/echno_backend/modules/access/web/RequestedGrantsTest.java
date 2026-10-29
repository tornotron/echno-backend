package org.tornotron.echno_backend.modules.access.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.web.RequestedGrants.Grant;
import org.tornotron.echno_backend.modules.access.web.dto.AccessGrantRequest;

class RequestedGrantsTest {

    @Test
    void everyActionBringsViewWithTheSameScope() {
        assertThat(RequestedGrants.parse(List.of(new AccessGrantRequest("wbs:edit", "ASSIGNED_PROJECTS"))))
                .containsExactly(
                        new Grant(AccessPermission.of("wbs:view"), AccessScope.ASSIGNED_PROJECTS),
                        new Grant(AccessPermission.of("wbs:edit"), AccessScope.ASSIGNED_PROJECTS));
    }

    @Test
    void repeatsAreDropped() {
        assertThat(RequestedGrants.parse(List.of(
                new AccessGrantRequest("wbs:view", "ALL"),
                new AccessGrantRequest("wbs:edit", "ALL"),
                new AccessGrantRequest("wbs:edit", "ALL")))).hasSize(2);
    }

    @Test
    void anAreasNamedActionsAreAccepted() {
        assertThat(RequestedGrants.parse(List.of(new AccessGrantRequest("progress-bills:certify", "ALL"))))
                .contains(new Grant(AccessPermission.of("progress-bills:certify"), AccessScope.ALL));
    }

    @Test
    void aPermissionOutsideTheCatalogIsRefused() {
        assertThatThrownBy(() -> RequestedGrants.parse(List.of(new AccessGrantRequest("wbs:certify", "ALL"))))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("wbs:certify");
        assertThatThrownBy(() -> RequestedGrants.parse(List.of(new AccessGrantRequest("payroll:view", "ALL"))))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void aMalformedPermissionOrScopeIsRefused() {
        assertThatThrownBy(() -> RequestedGrants.parse(List.of(new AccessGrantRequest("wbs", "ALL"))))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> RequestedGrants.parse(List.of(new AccessGrantRequest("wbs:view", "MINE"))))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("MINE");
    }
}
