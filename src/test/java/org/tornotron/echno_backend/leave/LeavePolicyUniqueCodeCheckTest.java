package org.tornotron.echno_backend.leave;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.leave.dto.LeavePolicyCreationDto;
import org.tornotron.echno_backend.leave.mapper.LeavePolicyMapper;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.user.UserContextService;

import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * One leave-type code per organization is the rule the create path is meant to keep. It asked
 * the wrong question twice, and each way of asking it lets a caller past.
 *
 * <p>The policy is written into the organization {@code TenantContext} names, but the duplicate
 * check was keyed on the {@code organizationId} the caller put in the body. {@code LeavePolicy}
 * carries the {@code orgFilter}, so any other value makes the predicate unsatisfiable and the
 * check silently passes. And the code is stored uppercased while the check ran against the
 * casing as it arrived, so the same code in lower case also passes.
 *
 * <p>Neither produces a duplicate row. {@code uk_leave_policy_org_type} is a real constraint and
 * the insert fails on it. What is lost is the refusal the service was written to give: instead of
 * a 409 naming the code that clashed, the caller gets the generic data-integrity 409 the handler
 * emits for any constraint, which says nothing about which field was wrong. That is a smaller
 * defect than the issue recorded, and it is still a defect: the check exists precisely so the
 * answer names the problem.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeavePolicyUniqueCodeCheckTest {

    private static final long TENANT = 100L;
    private static final long SOMEONE_ELSES = 200L;
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

        // The tenant already holds a SICK policy. Every case below tries to add a second.
        lenient().when(policyRepository.existsByOrganizationIdAndLeaveTypeCode(TENANT, "SICK"))
                .thenReturn(true);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void aSecondPolicyForTheSameCodeIsRefused() {
        assertThatThrownBy(() -> service.createPolicy(policy(TENANT, "SICK")))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void namingSomeoneElsesOrganizationInTheBodyDoesNotSkipTheCheck() {
        // The policy is written into the tenant either way, so the id in the body decides
        // nothing except, before this, whether the rule was applied at all.
        assertThatThrownBy(() -> service.createPolicy(policy(SOMEONE_ELSES, "SICK")))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void sendingTheCodeInLowerCaseDoesNotSkipTheCheckEither() {
        // The stored value is uppercased on the way in, so a check against the raw input is a
        // check against a value the table never holds.
        assertThatThrownBy(() -> service.createPolicy(policy(TENANT, "sick")))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void theCasingTheCodeIsStoredInDoesNotDependOnTheJvmLocale() {
        // Uppercasing without a Locale uses the JVM default. Under tr-TR "sick" uppercases to
        // "SİCK" with a dotted capital I, which is not the "SICK" the tenant already holds, so
        // the check passes and the row is written under a key no other environment would
        // produce for the same input. See issue #719.
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            assertThatThrownBy(() -> service.createPolicy(policy(TENANT, "sick")))
                    .isInstanceOf(DuplicateResourceException.class);
        } finally {
            Locale.setDefault(original);
        }
    }

    private static LeavePolicyCreationDto policy(long organizationId, String leaveTypeCode) {
        LeavePolicyCreationDto dto = new LeavePolicyCreationDto();
        dto.setOrganizationId(organizationId);
        dto.setLeaveTypeCode(leaveTypeCode);
        dto.setLeaveTypeName("Sick leave");
        dto.setAnnualQuota(12.0);
        return dto;
    }
}
