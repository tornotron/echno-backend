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
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
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

import java.time.LocalDateTime;
import java.util.HashMap;
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

    private ProjectInviteCodeService service;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        service = new ProjectInviteCodeService(inviteCodeRepository, employeeService, organizationRepository,
                fileStorageService, employeeRepository, projectInviteCodeMapper, organizationMapper);
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
                .isThrownBy(() -> service.generateInviteCode(generationDto(), ORG));
        verify(inviteCodeRepository, never()).save(any());
    }

    @Test
    void generateInviteCode_managerWithoutManagerRole_throwsNotFound() {
        when(organizationRepository.findById(ORG)).thenReturn(Optional.of(organization()));
        when(employeeRepository.existsByIdAndOrgRolesIn(eq(MANAGER_ID), any())).thenReturn(false);

        InviteCodeGenerationDto dto = generationDto();
        dto.setManagerId(MANAGER_ID);

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.generateInviteCode(dto, ORG));
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

        service.generateInviteCode(dto, ORG);

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
    void generateInviteCode_notPersisted_throwsDatabaseError() {
        when(organizationRepository.findById(ORG)).thenReturn(Optional.of(organization()));
        // save returns an entity whose id is still null -> persistence failure
        when(inviteCodeRepository.save(any(ProjectInviteCode.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatExceptionOfType(DatabaseOperationException.class)
                .isThrownBy(() -> service.generateInviteCode(generationDto(), ORG));
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
        lenient().when(employeeRepository.existsByIdAndOrgRolesIn(MANAGER_ID, managerRoles)).thenReturn(true);
        when(inviteCodeRepository.save(any(ProjectInviteCode.class))).thenAnswer(inv -> {
            ProjectInviteCode saved = inv.getArgument(0);
            saved.setId(INVITE_ID);
            return saved;
        });
        when(projectInviteCodeMapper.toDto(any())).thenReturn(new ProjectInviteCodeDto());

        InviteCodeGenerationDto dto = generationDto();
        dto.setManagerId(MANAGER_ID);

        service.generateInviteCode(dto, ORG);

        verify(employeeRepository).existsByIdAndOrgRolesIn(MANAGER_ID, managerRoles);
    }
}
