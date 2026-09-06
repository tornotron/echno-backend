package org.tornotron.echno_backend.projectInviteCode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.exception.DatabaseOperationException;
import org.tornotron.echno_backend.common.exception.InvalidInviteCodeException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.exception.TenantIdMissingException;
import org.tornotron.echno_backend.common.exception.TooManyAttemptsException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.ratelimit.InProcessAttemptBuckets;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.employee.EmployeeService;
import org.tornotron.echno_backend.employee.dto.EmployeeJoinOrgDto;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.organization.dto.OrganizationDto;
import org.tornotron.echno_backend.organization.mapper.OrganizationMapper;
import org.tornotron.echno_backend.projectInviteCode.dto.InviteCodeGenerationDto;
import org.tornotron.echno_backend.projectInviteCode.dto.InviteCodePatchDto;
import org.tornotron.echno_backend.projectInviteCode.dto.InviteCodeValidationDto;
import org.tornotron.echno_backend.projectInviteCode.dto.ProjectInviteCodeDto;
import org.tornotron.echno_backend.projectInviteCode.mapper.ProjectInviteCodeMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProjectInviteCodeService}. Repositories, the employee service, and
 * the mappers are mocked. The focus is the generation and redemption rules the service owns:
 * validating the organization and (optional) manager on generation, rejecting an expired /
 * inactive / used-up code on redemption, incrementing the use count and delegating the join,
 * the not-persisted guard, and the selective patch that only touches the fields the DTO sets.
 */
@ExtendWith(MockitoExtension.class)
class ProjectInviteCodeServiceTest {

    private static final Long ORG = 100L;
    private static final Long INVITE_ID = 3L;
    private static final Long USER_ID = 55L;
    private static final Long MANAGER_ID = 8L;

    @Mock private ProjectInviteCodeRepository inviteCodeRepository;
    @Mock private EmployeeService employeeService;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private FileStorageService fileStorageService;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ProjectInviteCodeMapper projectInviteCodeMapper;
    @Mock private OrganizationMapper organizationMapper;
    @Mock private org.tornotron.echno_backend.attendance.ShiftTimingRepository shiftTimingRepository;

    private ProjectInviteCodeService service;

    /**
     * A real limiter over real in-process buckets rather than a mock, because what the redemption
     * tests need to hold is that an attempt is actually spent and actually given back, and a mock
     * of the limiter would only record that it was called.
     */
    private InviteCodeRedemptionLimiter redemptionLimiter;
    private InviteCodeRedemptionProperties redemptionProperties;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        redemptionProperties = new InviteCodeRedemptionProperties();
        redemptionLimiter = new InviteCodeRedemptionLimiter(new InProcessAttemptBuckets(), redemptionProperties);
        service = new ProjectInviteCodeService(inviteCodeRepository, employeeService, organizationRepository,
                fileStorageService, employeeRepository, projectInviteCodeMapper, organizationMapper,
                shiftTimingRepository, redemptionLimiter);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Organization organization() {
        Organization org = new Organization();
        org.setId(ORG);
        return org;
    }

    private InviteCodeGenerationDto generationDto() {
        InviteCodeGenerationDto dto = new InviteCodeGenerationDto();
        dto.setEmployeeName("Jane Doe");
        dto.setEmail("jane@example.com");
        dto.setDesignation("Engineer");
        return dto;
    }

    @Test
    void generateSecureFiveDigitNumber_alwaysFiveDigits() {
        for (int i = 0; i < 200; i++) {
            int code = service.generateSecureFiveDigitNumber();
            assertThat(code).isBetween(10000, 99999);
        }
    }

    @Test
    void generateInviteCode_unknownOrganization_throwsNotFound() {
        when(organizationRepository.findById(ORG)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.generateInviteCode(generationDto()));
        verify(inviteCodeRepository, never()).save(any());
    }

    @Test
    void generateInviteCode_managerWithoutManagerRole_throwsNotFound() {
        when(organizationRepository.findById(ORG)).thenReturn(Optional.of(organization()));
        when(employeeRepository.existsByIdAndOrganization_IdAndOrgRolesIn(eq(MANAGER_ID), eq(ORG), any())).thenReturn(false);

        InviteCodeGenerationDto dto = generationDto();
        dto.setManagerId(MANAGER_ID);

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.generateInviteCode(dto));
        verify(inviteCodeRepository, never()).save(any());
    }

    @Test
    void generateInviteCode_valid_persistsCodeWithExpiryAndZeroUses() {
        when(organizationRepository.findById(ORG)).thenReturn(Optional.of(organization()));
        when(inviteCodeRepository.save(any(ProjectInviteCode.class))).thenAnswer(inv -> {
            ProjectInviteCode saved = inv.getArgument(0);
            saved.setId(INVITE_ID);
            return saved;
        });
        when(projectInviteCodeMapper.toDto(any())).thenReturn(new ProjectInviteCodeDto());

        InviteCodeGenerationDto dto = generationDto();
        dto.setMaxUses(4);
        dto.setValidityDays(10);

        service.generateInviteCode(dto);

        ArgumentCaptor<ProjectInviteCode> captor = ArgumentCaptor.forClass(ProjectInviteCode.class);
        verify(inviteCodeRepository).save(captor.capture());
        ProjectInviteCode saved = captor.getValue();
        assertThat(saved.getCode()).isBetween(10000, 99999);
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getMaxUses()).isEqualTo(4);
        assertThat(saved.getCurrentUses()).isZero();
        assertThat(saved.getExpiryDate()).isAfter(LocalDateTime.now().plusDays(9));
        assertThat(saved.getEmployeeDetails()).containsEntry("email", "jane@example.com");
    }

    @Test
    void generateInviteCode_withShiftTimingId_resolvesShiftAndStoresIdInDetails() {
        Long shiftId = 9L;
        org.tornotron.echno_backend.attendance.ShiftTiming shift =
                new org.tornotron.echno_backend.attendance.ShiftTiming();
        shift.setId(shiftId);
        when(organizationRepository.findById(ORG)).thenReturn(Optional.of(organization()));
        when(shiftTimingRepository.findByIdAndOrganization_Id(shiftId, ORG)).thenReturn(Optional.of(shift));
        when(inviteCodeRepository.save(any(ProjectInviteCode.class))).thenAnswer(inv -> {
            ProjectInviteCode saved = inv.getArgument(0);
            saved.setId(INVITE_ID);
            return saved;
        });
        when(projectInviteCodeMapper.toDto(any())).thenReturn(new ProjectInviteCodeDto());

        InviteCodeGenerationDto dto = generationDto();
        dto.setShiftTimingId(shiftId);

        service.generateInviteCode(dto);

        ArgumentCaptor<ProjectInviteCode> captor = ArgumentCaptor.forClass(ProjectInviteCode.class);
        verify(inviteCodeRepository).save(captor.capture());
        ProjectInviteCode saved = captor.getValue();
        assertThat(saved.getShiftTiming()).isSameAs(shift);
        assertThat(saved.getEmployeeDetails()).containsEntry("shiftTimingId", shiftId);
    }

    @Test
    void generateInviteCode_unknownShiftTimingId_throwsNotFound() {
        Long shiftId = 9L;
        when(organizationRepository.findById(ORG)).thenReturn(Optional.of(organization()));
        when(shiftTimingRepository.findByIdAndOrganization_Id(shiftId, ORG)).thenReturn(Optional.empty());

        InviteCodeGenerationDto dto = generationDto();
        dto.setShiftTimingId(shiftId);

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.generateInviteCode(dto));
        verify(inviteCodeRepository, never()).save(any());
    }

    @Test
    void generateInviteCode_notPersisted_throwsDatabaseError() {
        when(organizationRepository.findById(ORG)).thenReturn(Optional.of(organization()));
        // save returns an entity whose id is still null -> persistence failure
        when(inviteCodeRepository.save(any(ProjectInviteCode.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatExceptionOfType(DatabaseOperationException.class)
                .isThrownBy(() -> service.generateInviteCode(generationDto()));
    }

    private ProjectInviteCode storedCode(boolean active, LocalDateTime expiry, int maxUses, int currentUses) {
        ProjectInviteCode code = new ProjectInviteCode();
        code.setId(INVITE_ID);
        code.setCode(12345);
        code.setActive(active);
        code.setExpiryDate(expiry);
        code.setMaxUses(maxUses);
        code.setCurrentUses(currentUses);
        code.setOrganization(organization());
        Map<String, Object> details = new HashMap<>();
        details.put("designation", "Engineer");
        details.put("email", "jane@example.com");
        code.setEmployeeDetails(details);
        return code;
    }

    private InviteCodeValidationDto validationDto() {
        InviteCodeValidationDto dto = new InviteCodeValidationDto();
        dto.setCode("12345");
        return dto;
    }

    @Test
    void validateAndUse_unknownCode_throwsNotFound() {
        when(inviteCodeRepository.findByCode(12345)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.validateAndUseInviteCode(validationDto(), USER_ID));
    }

    @Test
    void validateAndUse_expiredCode_throwsInvalid() {
        when(inviteCodeRepository.findByCode(12345))
                .thenReturn(Optional.of(storedCode(true, LocalDateTime.now().minusDays(1), 5, 0)));

        assertThatExceptionOfType(InvalidInviteCodeException.class)
                .isThrownBy(() -> service.validateAndUseInviteCode(validationDto(), USER_ID));
        verify(employeeService, never()).joinOrganization(anyLong(), anyLong(), any());
    }

    @Test
    void validateAndUse_inactiveCode_throwsInvalid() {
        when(inviteCodeRepository.findByCode(12345))
                .thenReturn(Optional.of(storedCode(false, LocalDateTime.now().plusDays(5), 5, 0)));

        assertThatExceptionOfType(InvalidInviteCodeException.class)
                .isThrownBy(() -> service.validateAndUseInviteCode(validationDto(), USER_ID));
        verify(employeeService, never()).joinOrganization(anyLong(), anyLong(), any());
    }

    @Test
    void validateAndUse_maxUsesReached_throwsInvalid() {
        when(inviteCodeRepository.findByCode(12345))
                .thenReturn(Optional.of(storedCode(true, LocalDateTime.now().plusDays(5), 2, 2)));

        assertThatExceptionOfType(InvalidInviteCodeException.class)
                .isThrownBy(() -> service.validateAndUseInviteCode(validationDto(), USER_ID));
        verify(employeeService, never()).joinOrganization(anyLong(), anyLong(), any());
    }

    @Test
    void validateAndUse_valid_joinsOrgIncrementsUsesAndMapsOrganization() {
        ProjectInviteCode code = storedCode(true, LocalDateTime.now().plusDays(5), 5, 1);
        code.getEmployeeDetails().put("managerId", 8);
        code.getEmployeeDetails().put("salary", 55000.0);
        code.getEmployeeDetails().put("shiftTimingId", 9);
        when(inviteCodeRepository.findByCode(12345)).thenReturn(Optional.of(code));
        OrganizationDto orgDto = new OrganizationDto();
        when(organizationMapper.toDto(any())).thenReturn(orgDto);

        OrganizationDto result = service.validateAndUseInviteCode(validationDto(), USER_ID);

        assertThat(result).isSameAs(orgDto);
        assertThat(code.getCurrentUses()).isEqualTo(2);
        ArgumentCaptor<EmployeeJoinOrgDto> captor = ArgumentCaptor.forClass(EmployeeJoinOrgDto.class);
        verify(employeeService).joinOrganization(eq(USER_ID), eq(ORG), captor.capture());
        EmployeeJoinOrgDto joinDto = captor.getValue();
        assertThat(joinDto.getEmail()).isEqualTo("jane@example.com");
        assertThat(joinDto.getManagerId()).isEqualTo(8L);
        assertThat(joinDto.getSalary()).isEqualTo(55000.0);
        // The invite's stored shift id is carried into the join so the new employee is
        // given that structured shift.
        assertThat(joinDto.getShiftTimingId()).isEqualTo(9L);
    }

    @Test
    void patchInviteCode_unknownCode_throwsNotFound() {
        when(inviteCodeRepository.findByIdAndOrganization_Id(INVITE_ID, ORG)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.patchInviteCode(INVITE_ID, new InviteCodePatchDto()));
    }

    @Test
    void patchInviteCode_updatesOnlyProvidedFields() {
        ProjectInviteCode code = storedCode(true, LocalDateTime.now().plusDays(5), 5, 1);
        when(inviteCodeRepository.findByIdAndOrganization_Id(INVITE_ID, ORG)).thenReturn(Optional.of(code));
        when(inviteCodeRepository.save(any(ProjectInviteCode.class))).thenAnswer(inv -> inv.getArgument(0));
        when(projectInviteCodeMapper.toDto(any())).thenReturn(new ProjectInviteCodeDto());

        InviteCodePatchDto patch = new InviteCodePatchDto();
        patch.setIsActive(false);
        // maxUses and currentUses left null -> must not change

        service.patchInviteCode(INVITE_ID, patch);

        assertThat(code.isActive()).isFalse();
        assertThat(code.getMaxUses()).isEqualTo(5);
        assertThat(code.getCurrentUses()).isEqualTo(1);
    }

    @Test
    void patchInviteCode_updatesUsageCounters() {
        ProjectInviteCode code = storedCode(true, LocalDateTime.now().plusDays(5), 5, 1);
        when(inviteCodeRepository.findByIdAndOrganization_Id(INVITE_ID, ORG)).thenReturn(Optional.of(code));
        when(inviteCodeRepository.save(any(ProjectInviteCode.class))).thenAnswer(inv -> inv.getArgument(0));
        when(projectInviteCodeMapper.toDto(any())).thenReturn(new ProjectInviteCodeDto());

        InviteCodePatchDto patch = new InviteCodePatchDto();
        patch.setMaxUses(10);
        patch.setCurrentUses(3);

        service.patchInviteCode(INVITE_ID, patch);

        assertThat(code.getMaxUses()).isEqualTo(10);
        assertThat(code.getCurrentUses()).isEqualTo(3);
        assertThat(code.isActive()).isTrue();
    }

    // Guards against an accidental widening of the manager-role set used in generation.
    @Test
    void generateInviteCode_checksManagerRolesSet() {
        when(organizationRepository.findById(ORG)).thenReturn(Optional.of(organization()));
        Set<OrgRole> managerRoles = OrgRole.getManagerRoles();
        lenient().when(employeeRepository.existsByIdAndOrganization_IdAndOrgRolesIn(MANAGER_ID, ORG, managerRoles)).thenReturn(true);
        when(inviteCodeRepository.save(any(ProjectInviteCode.class))).thenAnswer(inv -> {
            ProjectInviteCode saved = inv.getArgument(0);
            saved.setId(INVITE_ID);
            return saved;
        });
        when(projectInviteCodeMapper.toDto(any())).thenReturn(new ProjectInviteCodeDto());

        InviteCodeGenerationDto dto = generationDto();
        dto.setManagerId(MANAGER_ID);

        service.generateInviteCode(dto);

        verify(employeeRepository).existsByIdAndOrganization_IdAndOrgRolesIn(MANAGER_ID, ORG, managerRoles);
    }

    // --- The organization an invite code is bound to comes from the session (#687) ---

    @Test
    void generateInviteCode_bindsTheCodeToTheSessionOrganization() {
        // The method takes no organization argument at all, so there is no second key for a
        // caller-supplied id to travel on. This asserts the one that remains is the session's.
        when(organizationRepository.findById(ORG)).thenReturn(Optional.of(organization()));
        when(inviteCodeRepository.save(any(ProjectInviteCode.class))).thenAnswer(inv -> {
            ProjectInviteCode saved = inv.getArgument(0);
            saved.setId(INVITE_ID);
            return saved;
        });
        when(projectInviteCodeMapper.toDto(any())).thenReturn(new ProjectInviteCodeDto());

        service.generateInviteCode(generationDto());

        ArgumentCaptor<ProjectInviteCode> captor = ArgumentCaptor.forClass(ProjectInviteCode.class);
        verify(inviteCodeRepository).save(captor.capture());
        assertThat(captor.getValue().getOrganization().getId()).isEqualTo(ORG);
        verify(organizationRepository).findById(ORG);
    }

    @Test
    void generateInviteCode_withNoTenantInContext_mintsNothing() {
        TenantContext.clear();

        assertThatExceptionOfType(TenantIdMissingException.class)
                .isThrownBy(() -> service.generateInviteCode(generationDto()));
        verify(organizationRepository, never()).findById(any());
        verify(inviteCodeRepository, never()).save(any());
    }

    @Test
    void generateInviteCode_checksTheManagerWithinTheSessionOrganization() {
        // The refusal message claims the manager was looked for in this organization. Before
        // this it was looked for anywhere, and the claim rested on the Hibernate filter
        // happening to be enabled rather than on the query.
        when(organizationRepository.findById(ORG)).thenReturn(Optional.of(organization()));
        when(employeeRepository.existsByIdAndOrganization_IdAndOrgRolesIn(
                eq(MANAGER_ID), eq(ORG), any())).thenReturn(false);

        InviteCodeGenerationDto dto = generationDto();
        dto.setManagerId(MANAGER_ID);

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.generateInviteCode(dto));
        verify(employeeRepository).existsByIdAndOrganization_IdAndOrgRolesIn(
                eq(MANAGER_ID), eq(ORG), any());
    }

    @Test
    void readAllProjectInviteCodes_readsTheSessionOrganization() {
        when(inviteCodeRepository.findByOrganization_Id(ORG)).thenReturn(List.of());

        service.readAllProjectInviteCodes();

        verify(inviteCodeRepository).findByOrganization_Id(ORG);
    }

    @Test
    void readAllProjectInviteCodes_withNoTenantInContext_readsNothing() {
        TenantContext.clear();

        assertThatExceptionOfType(TenantIdMissingException.class)
                .isThrownBy(() -> service.readAllProjectInviteCodes());
        verify(inviteCodeRepository, never()).findByOrganization_Id(any());
    }

    /**
     * Redemption resolves a bearer credential with no organization qualifier, and it cannot have
     * one, because the person redeeming holds no membership yet. That leaves the number of values
     * that may be offered as the only thing keeping the credential worth anything, so the count
     * has to be bounded and the bound has to bite before the lookup.
     */
    @Test
    void validateAndUse_pastTheAttemptAllowance_isRefusedWithoutLookingTheCodeUp() {
        redemptionProperties.setAttemptsPerCaller(2);
        when(inviteCodeRepository.findByCode(12345)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.validateAndUseInviteCode(validationDto(), USER_ID));
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.validateAndUseInviteCode(validationDto(), USER_ID));

        assertThatExceptionOfType(TooManyAttemptsException.class)
                .isThrownBy(() -> service.validateAndUseInviteCode(validationDto(), USER_ID));

        // Twice, not three times. The third attempt was refused before the repository was reached,
        // which is what makes this a limit on attempts rather than on the answers to them.
        verify(inviteCodeRepository, org.mockito.Mockito.times(2)).findByCode(12345);
    }

    /**
     * A code that turns out to be good gives the attempt back, so the person redeeming the
     * invitation they were actually sent never approaches the allowance.
     */
    @Test
    void validateAndUse_acceptedCode_costsNoAttempt() {
        redemptionProperties.setAttemptsPerCaller(1);
        ProjectInviteCode code = storedCode(true, LocalDateTime.now().plusDays(5), 500, 0);
        when(inviteCodeRepository.findByCode(12345)).thenReturn(Optional.of(code));
        when(organizationMapper.toDto(any())).thenReturn(new OrganizationDto());

        for (int i = 0; i < 5; i++) {
            service.validateAndUseInviteCode(validationDto(), USER_ID);
        }

        verify(inviteCodeRepository, org.mockito.Mockito.times(5)).findByCode(12345);
    }

    /**
     * Every way a code can be turned down spends the attempt. A refusal that cost nothing would be
     * a free probe, and the useful probe is precisely the one that fails.
     */
    @Test
    void validateAndUse_everyKindOfRefusal_spendsTheAttempt() {
        redemptionProperties.setAttemptsPerCaller(3);
        when(inviteCodeRepository.findByCode(12345))
                .thenReturn(Optional.of(storedCode(true, LocalDateTime.now().minusDays(1), 5, 0)))
                .thenReturn(Optional.of(storedCode(false, LocalDateTime.now().plusDays(5), 5, 0)))
                .thenReturn(Optional.of(storedCode(true, LocalDateTime.now().plusDays(5), 2, 2)));

        assertThatExceptionOfType(InvalidInviteCodeException.class)
                .isThrownBy(() -> service.validateAndUseInviteCode(validationDto(), USER_ID));
        assertThatExceptionOfType(InvalidInviteCodeException.class)
                .isThrownBy(() -> service.validateAndUseInviteCode(validationDto(), USER_ID));
        assertThatExceptionOfType(InvalidInviteCodeException.class)
                .isThrownBy(() -> service.validateAndUseInviteCode(validationDto(), USER_ID));

        assertThatExceptionOfType(TooManyAttemptsException.class)
                .isThrownBy(() -> service.validateAndUseInviteCode(validationDto(), USER_ID));
    }

    /**
     * The submitted code is five characters, which the request body's constraint enforces, but
     * nothing said they were digits. A five-letter submission used to reach {@code Integer.parseInt}
     * and answer 500, so a malformed code was met with a server error instead of a refusal.
     */
    @Test
    void validateAndUse_aCodeThatIsNotANumber_isRefusedRatherThanFailing() {
        InviteCodeValidationDto dto = new InviteCodeValidationDto();
        dto.setCode("ABCDE");

        assertThatExceptionOfType(InvalidInviteCodeException.class)
                .isThrownBy(() -> service.validateAndUseInviteCode(dto, USER_ID));
        verify(inviteCodeRepository, never()).findByCode(org.mockito.ArgumentMatchers.anyInt());
    }

    /** A malformed code is still an attempt, or it would be the free probe the others are not. */
    @Test
    void validateAndUse_aCodeThatIsNotANumber_stillSpendsTheAttempt() {
        redemptionProperties.setAttemptsPerCaller(1);
        InviteCodeValidationDto dto = new InviteCodeValidationDto();
        dto.setCode("ABCDE");

        assertThatExceptionOfType(InvalidInviteCodeException.class)
                .isThrownBy(() -> service.validateAndUseInviteCode(dto, USER_ID));
        assertThatExceptionOfType(TooManyAttemptsException.class)
                .isThrownBy(() -> service.validateAndUseInviteCode(dto, USER_ID));
    }

    /**
     * Expiry and a use limit are separately already true of a code: {@code validityDays} defaults
     * to five days and {@code maxUses} to one, so the shipped default is a single-use invitation
     * that dies in under a week. Neither bounds how many values may be offered, which is what the
     * attempt allowance is for, and pinning the defaults here is what keeps a later edit from
     * quietly turning the default code into a permanent one.
     */
    @Test
    void aGeneratedCodeIsSingleUseAndShortLivedByDefault() {
        InviteCodeGenerationDto dto = new InviteCodeGenerationDto();

        assertThat(dto.getMaxUses()).isEqualTo(1);
        assertThat(dto.getValidityDays()).isEqualTo(5);
        assertThat(Duration.ofDays(dto.getValidityDays())).isLessThanOrEqualTo(Duration.ofDays(7));
    }
}
