package org.tornotron.echno_backend.modules.access.guard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.approval.SelfApprovalPolicy;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.workprogress.billing.service.ContractBillService;
import org.tornotron.echno_backend.project.ProjectService;

/** The permissions only a service checks are still counted as checked, so the Roles screen offers them. */
class ChecksAccessDeclarationTest {

    @Test
    void eachServiceCheckIsDeclared() {
        assertThat(EnforcedPermissions.declaredOn(SelfApprovalPolicy.class))
                .containsExactly(AccessPermission.of("org-admin:approve-own-documents"));
        assertThat(EnforcedPermissions.declaredOn(ContractBillService.class))
                .containsExactly(AccessPermission.of("org-admin:approve-own-documents"));
        assertThat(EnforcedPermissions.declaredOn(ProjectService.class))
                .containsExactly(AccessPermission.of("projects:complete"));
    }

    @Test
    void aClassWithNoListDeclaresNothing() {
        assertThat(EnforcedPermissions.declaredOn(String.class)).isEmpty();
    }
}
