package org.tornotron.echno_backend.leave;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.leave.dto.LeavePolicyCreationDto;
import org.tornotron.echno_backend.leave.mapper.LeavePolicyMapper;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.user.UserContextService;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A leave policy created without a value for a field keeps the value the entity declares for it.
 *
 * <p>{@code createPolicy} ran every setter with whatever arrived, so a payload carrying an
 * explicit null overwrote the entity's initialiser and Hibernate wrote NULL. Every one of these
 * columns is nullable, so nothing refused the row, and the column default never applied either:
 * a default fills an absent column, and the insert named the column.
 *
 * <p>What follows is behaviour rather than cosmetics, because {@code LeaveRequestValidator} is
 * null-safe throughout. A null {@code allowHalfDay} makes {@code Boolean.TRUE.equals(...)} false
 * and half-day requests are refused on a policy meant to allow them; a null
 * {@code minDaysPerRequest} or {@code advanceNoticeDays} makes the {@code != null} guards skip
 * their checks, so the limits the policy was created with go unenforced. The policy reads as
 * configured and behaves as though it were not.
 *
 * <p>The narrow trigger is worth stating: {@code LeavePolicyCreationDto} carries the same
 * initialisers, so a body that simply omits the field never reaches this. It takes an explicit
 * JSON null, which is what a client sends when it serialises a form with the field cleared.
 *
 * <p>Against the old code the first two tests fail with null in every field. See issue #741.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeavePolicyDeclaredDefaultsTest {

    private static final long TENANT = 100L;
    private static final long OTHER_TENANT = 200L;
    private static final String EMAIL = "hr@example.test";

    @Mock private LeavePolicyRepository policyRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private UserContextService userContextService;
    @Mock private LeavePolicyMapper leavePolicyMapper;

    private LeavePolicyService service;

    @BeforeEach
    void setUp() {
        service = new LeavePolicyService(policyRepository, organizationRepository,
                employeeRepository, userContextService, leavePolicyMapper);

        TenantContext.setCurrentOrgId(TENANT);

        Organization tenant = new Organization();
        tenant.setId(TENANT);
        when(userContextService.getCurrentUserEmail()).thenReturn(EMAIL);
        when(organizationRepository.findByIdAndUserEmail(TENANT, EMAIL))
                .thenReturn(Optional.of(tenant));
        when(policyRepository.save(any(LeavePolicy.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void aPayloadOfExplicitNullsLeavesEveryDeclaredDefaultStanding() {
        service.createPolicy(everyOptionalFieldCleared());

        LeavePolicy written = theSavedPolicy();
        assertThat(written.getMinDaysPerRequest()).isEqualTo(0.5);
        assertThat(written.getAdvanceNoticeDays()).isZero();
        assertThat(written.getRequiresAttachment()).isFalse();
        assertThat(written.getApplicableGenders()).isEqualTo("ALL");
        assertThat(written.getMinServiceMonths()).isZero();
        assertThat(written.getAllowHalfDay()).isTrue();
        assertThat(written.getIsPaid()).isTrue();
        assertThat(written.getDisplayOrder()).isZero();
        assertThat(written.getMultiLevelApprovalEnabled()).isTrue();
    }

    @Test
    void aDuplicateDoesNotInheritANullTheSourcePolicyShouldNeverHaveHeld() {
        // Policies written before the repair can hold these nulls, and copying one forward would
        // spread a state the policy was never configured into.
        LeavePolicy source = new LeavePolicy();
        source.setId(7L);
        source.setLeaveTypeCode("SICK");
        source.setLeaveTypeName("Sick leave");
        source.setAnnualQuota(12.0);
        source.setMinDaysPerRequest(null);
        source.setAdvanceNoticeDays(null);
        source.setRequiresAttachment(null);
        source.setApplicableGenders(null);
        source.setMinServiceMonths(null);
        source.setAllowHalfDay(null);
        source.setIsPaid(null);
        source.setDisplayOrder(null);
        source.setMultiLevelApprovalEnabled(null);

        Organization target = new Organization();
        target.setId(OTHER_TENANT);
        when(policyRepository.findByIdAndOrganization_Id(7L, TENANT)).thenReturn(Optional.of(source));
        when(organizationRepository.findByIdAndUserEmail(OTHER_TENANT, EMAIL))
                .thenReturn(Optional.of(target));
        when(policyRepository.countWithLeaveTypeCodeInOrganizationUnfiltered(OTHER_TENANT, "SICK"))
                .thenReturn(0L);

        service.duplicatePolicy(7L, OTHER_TENANT);

        LeavePolicy written = theSavedPolicy();
        assertThat(written.getAllowHalfDay()).isTrue();
        assertThat(written.getMinDaysPerRequest()).isEqualTo(0.5);
        assertThat(written.getAdvanceNoticeDays()).isZero();
        assertThat(written.getApplicableGenders()).isEqualTo("ALL");
    }

    @Test
    void aValueTheCallerDidSendIsStillTheValueThatIsWritten() {
        // The guard has to distinguish an absent value from a chosen one, and false is a chosen
        // one. A repair that fell back to the initialiser whenever the field was falsy would read
        // as fixed here and quietly refuse to let anyone turn half days off.
        LeavePolicyCreationDto dto = everyOptionalFieldCleared();
        dto.setAllowHalfDay(false);
        dto.setIsPaid(false);
        dto.setMinDaysPerRequest(1.0);
        dto.setAdvanceNoticeDays(7);
        dto.setDisplayOrder(3);
        dto.setMultiLevelApprovalEnabled(false);

        service.createPolicy(dto);

        LeavePolicy written = theSavedPolicy();
        assertThat(written.getAllowHalfDay()).isFalse();
        assertThat(written.getIsPaid()).isFalse();
        assertThat(written.getMinDaysPerRequest()).isEqualTo(1.0);
        assertThat(written.getAdvanceNoticeDays()).isEqualTo(7);
        assertThat(written.getDisplayOrder()).isEqualTo(3);
        assertThat(written.getMultiLevelApprovalEnabled()).isFalse();
    }

    /**
     * The payload a client sends when it serialises a form with every optional field cleared:
     * the required three, and an explicit null everywhere else.
     */
    private LeavePolicyCreationDto everyOptionalFieldCleared() {
        LeavePolicyCreationDto dto = new LeavePolicyCreationDto();
        dto.setLeaveTypeCode("CASUAL");
        dto.setLeaveTypeName("Casual leave");
        dto.setAnnualQuota(12.0);
        dto.setMinDaysPerRequest(null);
        dto.setAdvanceNoticeDays(null);
        dto.setRequiresAttachment(null);
        dto.setApplicableGenders(null);
        dto.setMinServiceMonths(null);
        dto.setAllowHalfDay(null);
        dto.setIsPaid(null);
        dto.setDisplayOrder(null);
        dto.setMultiLevelApprovalEnabled(null);
        return dto;
    }

    private LeavePolicy theSavedPolicy() {
        ArgumentCaptor<LeavePolicy> captor = ArgumentCaptor.forClass(LeavePolicy.class);
        verify(policyRepository).save(captor.capture());
        return captor.getValue();
    }
}
