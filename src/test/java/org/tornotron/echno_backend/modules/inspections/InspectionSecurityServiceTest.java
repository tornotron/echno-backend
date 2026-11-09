package org.tornotron.echno_backend.modules.inspections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.modules.inspections.domain.Ncr;
import org.tornotron.echno_backend.modules.inspections.domain.Reinspection;
import org.tornotron.echno_backend.modules.inspections.repositories.NcrRepository;
import org.tornotron.echno_backend.modules.inspections.repositories.ReinspectionRepository;
import org.tornotron.echno_backend.modules.inspections.service.InspectionSecurityService;

/**
 * The discipline boundary on an NCR sign-off: a safety officer cannot close a quality
 * non-conformance and a QA engineer cannot close a safety one. The guard asks the matrix for both
 * sign-off actions; the bean picks the one the report's discipline calls for.
 *
 * <p>Plain Mockito, no Spring context: the rule is which answer counts for which report.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InspectionSecurityServiceTest {

    private static final UUID NCR_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID REINSPECTION_ID = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");
    private static final String[] OBSERVATION_INTAKE = {"system-admin", "observation-producer"};

    @Mock
    private OrganizationSecurityService orgSecurity;
    @Mock
    private NcrRepository ncrRepo;
    @Mock
    private ReinspectionRepository reinspectionRepo;

    private InspectionSecurityService security() {
        return new InspectionSecurityService(orgSecurity, ncrRepo, reinspectionRepo, OBSERVATION_INTAKE);
    }

    @Test
    void aQualityReportTakesTheQualitySignOff() {
        when(ncrRepo.findByIdScoped(NCR_ID)).thenReturn(Optional.of(ncrOfType(NcrType.QUALITY)));

        assertThat(security().maySignOffNcr(NCR_ID, true, false)).isTrue();
        assertThat(security().maySignOffNcr(NCR_ID, false, true)).isFalse();
    }

    @Test
    void aSafetyReportTakesTheSafetySignOff() {
        when(ncrRepo.findByIdScoped(NCR_ID)).thenReturn(Optional.of(ncrOfType(NcrType.SAFETY)));

        assertThat(security().maySignOffNcr(NCR_ID, false, true)).isTrue();
        assertThat(security().maySignOffNcr(NCR_ID, true, false)).isFalse();
    }

    @Test
    void aReportThatIsNotInThisTenantIsLeftToTheServiceToReportMissing() {
        // a 403 here would read as "it exists and you may not touch it", which is both
        // wrong and a worse answer than the 404 the service already gives
        when(ncrRepo.findByIdScoped(NCR_ID)).thenReturn(Optional.empty());

        assertThat(security().maySignOffNcr(NCR_ID, false, false)).isTrue();
    }

    @Test
    void aReinspectionOfAnNcrTakesThatReportsSignOff() {
        when(reinspectionRepo.findByIdScoped(REINSPECTION_ID)).thenReturn(Optional.of(reinspectionOf(NCR_ID)));
        when(ncrRepo.findByIdScoped(NCR_ID)).thenReturn(Optional.of(ncrOfType(NcrType.SAFETY)));

        assertThat(security().mayRecordReinspectionOutcome(REINSPECTION_ID, false, true)).isTrue();
        assertThat(security().mayRecordReinspectionOutcome(REINSPECTION_ID, true, false)).isFalse();
    }

    @Test
    void aDefectOnlyReinspectionTakesTheQualitySignOff() {
        when(reinspectionRepo.findByIdScoped(REINSPECTION_ID)).thenReturn(Optional.of(reinspectionOf(null)));

        assertThat(security().mayRecordReinspectionOutcome(REINSPECTION_ID, true, false)).isTrue();
        assertThat(security().mayRecordReinspectionOutcome(REINSPECTION_ID, false, true)).isFalse();
    }

    @Test
    void aReinspectionThatIsNotInThisTenantIsLeftToTheService() {
        when(reinspectionRepo.findByIdScoped(REINSPECTION_ID)).thenReturn(Optional.empty());

        assertThat(security().mayRecordReinspectionOutcome(REINSPECTION_ID, false, false)).isTrue();
    }

    @Test
    void intakeIsCheckedAgainstTheProducerRoles() {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(OBSERVATION_INTAKE)).thenReturn(true);
        assertThat(security().canIntakeObservations()).isTrue();
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(OBSERVATION_INTAKE)).thenReturn(false);
        assertThat(security().canIntakeObservations()).isFalse();
    }

    private static Ncr ncrOfType(NcrType type) {
        Ncr ncr = new Ncr();
        ncr.setNcrNumber("NCR-2027-000001");
        ncr.setType(type);
        return ncr;
    }

    private static Reinspection reinspectionOf(UUID ncrId) {
        Reinspection reinspection = new Reinspection();
        reinspection.setNcrId(ncrId);
        return reinspection;
    }
}
