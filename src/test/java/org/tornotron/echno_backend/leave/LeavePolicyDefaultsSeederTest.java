package org.tornotron.echno_backend.leave;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.leave.enums.AccrualMethod;
import org.tornotron.echno_backend.leave.enums.LeaveApproverRole;
import org.tornotron.echno_backend.organization.Organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeavePolicyDefaultsSeederTest {

    @Mock private LeavePolicyRepository policyRepository;
    @Mock private TenantEntityHelper tenantEntityHelper;

    private LeavePolicyDefaultsSeeder seeder;
    private Organization organization;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(1L);
        organization = new Organization();
        organization.setId(1L);
        when(tenantEntityHelper.resolveCurrentOrganization()).thenReturn(organization);
        seeder = new LeavePolicyDefaultsSeeder(policyRepository, tenantEntityHelper);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aFreshOrganization_getsTheFiveDefaults_withPaternityAsSpecified() {
        when(policyRepository.existsByOrganizationIdAndLeaveTypeCode(eq(1L), any())).thenReturn(false);

        assertThat(seeder.seedDefaults()).isEqualTo(5);

        ArgumentCaptor<LeavePolicy> saved = ArgumentCaptor.forClass(LeavePolicy.class);
        verify(policyRepository, times(5)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(LeavePolicy::getLeaveTypeCode)
                .containsExactly("CL", "SL", "EL", "ML", "PL");
        assertThat(saved.getAllValues()).allSatisfy(p -> assertThat(p.getOrganization()).isSameAs(organization));

        LeavePolicy paternity = saved.getAllValues().get(4);
        assertThat(paternity.getLeaveTypeName()).isEqualTo("Paternity Leave");
        assertThat(paternity.getAnnualQuota()).isEqualTo(7.0);
        assertThat(paternity.getApplicableGenders()).isEqualTo("MALE");
        assertThat(paternity.getRequiresAttachment()).isTrue();
        assertThat(paternity.getAccrualMethod()).isEqualTo(AccrualMethod.IN_FULL_ON_QUALIFYING);
        assertThat(paternity.getApproverRole()).isEqualTo(LeaveApproverRole.REPORTING_MANAGER);
        assertThat(saved.getAllValues().get(3).getApplicableGenders()).isEqualTo("FEMALE");
    }

    @Test
    void aTypeTheOrganizationAlreadyHolds_isLeftAlone() {
        when(policyRepository.existsByOrganizationIdAndLeaveTypeCode(eq(1L), any())).thenReturn(true);
        when(policyRepository.existsByOrganizationIdAndLeaveTypeCode(1L, "PL")).thenReturn(false);

        assertThat(seeder.seedDefaults()).isEqualTo(1);

        ArgumentCaptor<LeavePolicy> saved = ArgumentCaptor.forClass(LeavePolicy.class);
        verify(policyRepository).save(saved.capture());
        assertThat(saved.getValue().getLeaveTypeCode()).isEqualTo("PL");
    }
}
