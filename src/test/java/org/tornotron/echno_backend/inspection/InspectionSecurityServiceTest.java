package org.tornotron.echno_backend.inspection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.inspection.domain.Ncr;
import org.tornotron.echno_backend.inspection.repositories.NcrRepository;
import org.tornotron.echno_backend.inspection.service.InspectionSecurityService;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The discipline boundary on an NCR sign-off: a safety officer cannot close a
 * quality non-conformance and a QA engineer cannot close a safety one. Both would
 * be a sign-off on work outside the signer's competence, and the closed report is
 * the record that somebody qualified accepted it.
 *
 * <p>Plain Mockito, no Spring context: the rule is which role list gets checked
 * for which report, and the test JVM caches every context it loads for the whole
 * run.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InspectionSecurityServiceTest {

    private static final UUID NCR_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private static final String[] READ = {"system-admin"};
    private static final String[] MANAGE = {"system-admin"};
    private static final String[] CHECKLIST = {"system-admin", "qa-engineer"};
    private static final String[] RAISE = {"system-admin"};
    private static final String[] CORRECTIVE = {"system-admin", "site-engineer"};
    private static final String[] QUALITY_SIGN_OFF = {"system-admin", "qa-engineer"};
    private static final String[] SAFETY_SIGN_OFF = {"system-admin", "safety-officer"};

    @Mock
    private OrganizationSecurityService orgSecurity;
    @Mock
    private NcrRepository ncrRepo;

    private InspectionSecurityService security() {
        return new InspectionSecurityService(orgSecurity, ncrRepo, READ, MANAGE, CHECKLIST,
                RAISE, CORRECTIVE, QUALITY_SIGN_OFF, SAFETY_SIGN_OFF);
    }

    @Test
    void aQualityReportIsCheckedAgainstTheQualitySignOffRoles() {
        when(ncrRepo.findByIdScoped(NCR_ID)).thenReturn(Optional.of(ncrOfType(NcrType.QUALITY)));
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(QUALITY_SIGN_OFF)).thenReturn(true);

        assertThat(security().canSignOffNcr(NCR_ID)).isTrue();

        verify(orgSecurity).hasAnyOrgRoleForCurrentTenant(QUALITY_SIGN_OFF);
        verify(orgSecurity, never()).hasAnyOrgRoleForCurrentTenant(SAFETY_SIGN_OFF);
    }

    @Test
    void aSafetyReportIsCheckedAgainstTheSafetySignOffRoles() {
        when(ncrRepo.findByIdScoped(NCR_ID)).thenReturn(Optional.of(ncrOfType(NcrType.SAFETY)));
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(SAFETY_SIGN_OFF)).thenReturn(true);

        assertThat(security().canSignOffNcr(NCR_ID)).isTrue();

        verify(orgSecurity).hasAnyOrgRoleForCurrentTenant(SAFETY_SIGN_OFF);
        verify(orgSecurity, never()).hasAnyOrgRoleForCurrentTenant(QUALITY_SIGN_OFF);
    }

    @Test
    void aSafetyOfficerCannotSignOffAQualityReport() {
        when(ncrRepo.findByIdScoped(NCR_ID)).thenReturn(Optional.of(ncrOfType(NcrType.QUALITY)));
        // holds the safety role and not the quality one
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(QUALITY_SIGN_OFF)).thenReturn(false);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(SAFETY_SIGN_OFF)).thenReturn(true);

        assertThat(security().canSignOffNcr(NCR_ID)).isFalse();
    }

    @Test
    void aQaEngineerCannotSignOffASafetyReport() {
        when(ncrRepo.findByIdScoped(NCR_ID)).thenReturn(Optional.of(ncrOfType(NcrType.SAFETY)));
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(SAFETY_SIGN_OFF)).thenReturn(false);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(QUALITY_SIGN_OFF)).thenReturn(true);

        assertThat(security().canSignOffNcr(NCR_ID)).isFalse();
    }

    @Test
    void aReportThatIsNotInThisTenantIsLeftToTheServiceToReportMissing() {
        // a 403 here would read as "it exists and you may not touch it", which is both
        // wrong and a worse answer than the 404 the service already gives
        when(ncrRepo.findByIdScoped(NCR_ID)).thenReturn(Optional.empty());

        assertThat(security().canSignOffNcr(NCR_ID)).isTrue();
        verify(orgSecurity, never()).hasAnyOrgRoleForCurrentTenant(any(String[].class));
    }

    @Test
    void theOtherActsCheckTheirOwnRoleList() {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(CHECKLIST)).thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(CORRECTIVE)).thenReturn(false);

        InspectionSecurityService security = security();
        assertThat(security.canDefineChecklists()).isTrue();
        assertThat(security.canReportCorrectiveAction()).isFalse();

        verify(orgSecurity).hasAnyOrgRoleForCurrentTenant(CHECKLIST);
        verify(orgSecurity).hasAnyOrgRoleForCurrentTenant(CORRECTIVE);
    }

    private static Ncr ncrOfType(NcrType type) {
        Ncr ncr = new Ncr();
        ncr.setNcrNumber("NCR-2027-000001");
        ncr.setType(type);
        return ncr;
    }
}
