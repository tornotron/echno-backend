package org.tornotron.echno_backend.attendance;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.tornotron.echno_backend.attendance.dto.AttendanceRegularizationDto;
import org.tornotron.echno_backend.attendance.mapper.AttendanceRegularizationMapper;
import org.tornotron.echno_backend.attendance.service.AttendanceActorResolver;
import org.tornotron.echno_backend.attendance.service.AttendanceCalculationService;
import org.tornotron.echno_backend.attendance.service.AttendanceRegularizationService;
import org.tornotron.echno_backend.attendance.service.AttendanceSettingsService;
import org.tornotron.echno_backend.common.approval.SelfApprovalPolicy;
import org.tornotron.echno_backend.common.exception.GlobalExceptionHandler;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.organization.OrganizationRepository;

/**
 * Both endpoints that read one regularization request, the tenant API's and its {@code /web} twin,
 * ask {@link RegularisationAccess#mayRead} with the ids stored on the request, and answer 200 or 403
 * on what it says. {@code RegularisationByIdReadIT} settles who it says yes to: the employee, whoever
 * decided the request and a record-management role, and nobody else.
 */
@ExtendWith(MockitoExtension.class)
class RegularisationByIdTwinsTest {

    private static final Long ORG = 3L;
    private static final Long REQUEST = 41L;
    private static final Long OWNER = 7L;
    private static final Long DECIDER = 9L;

    @Mock private AttendanceRegularizationRepository regularizationRepository;
    @Mock private AttendanceRepository attendanceRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private AttendanceSettingsService settingsService;
    @Mock private AttendanceCalculationService calculationService;
    @Mock private AttendanceRegularizationMapper regularizationMapper;
    @Mock private AttendanceActorResolver actorResolver;
    @Mock private SelfApprovalPolicy selfApprovalPolicy;
    @Mock private RegularisationAccess regularisationAccess;

    private AttendanceRegularizationService service;

    @BeforeEach
    void aDecidedRequest() {
        TenantContext.setCurrentOrgId(ORG);
        service = new AttendanceRegularizationService(regularizationRepository, attendanceRepository,
                organizationRepository, settingsService, calculationService, regularizationMapper,
                actorResolver, selfApprovalPolicy, regularisationAccess);
        Attendance attendance = Attendance.builder().employeeId(OWNER).build();
        AttendanceRegularization request = AttendanceRegularization.builder().id(REQUEST).attendance(attendance).build();
        request.setApprovedById(DECIDER);
        when(regularizationRepository.findByIdAndOrganization_Id(REQUEST, ORG)).thenReturn(Optional.of(request));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @ParameterizedTest
    @CsvSource({"/api/v1/attendance-regularizations/41", "/api/v1/attendance-regularizations/web/41"})
    void aCallerItAllowsReadsTheRequest(String path) throws Exception {
        when(regularisationAccess.mayRead(OWNER, DECIDER)).thenReturn(true);
        when(regularizationMapper.toDto(org.mockito.ArgumentMatchers.any()))
                .thenReturn(AttendanceRegularizationDto.builder().id(REQUEST).build());

        mockMvc().perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(REQUEST));
        verify(regularisationAccess).mayRead(OWNER, DECIDER);
    }

    @ParameterizedTest
    @CsvSource({"/api/v1/attendance-regularizations/41", "/api/v1/attendance-regularizations/web/41"})
    void aCallerItRefusesGetsA403(String path) throws Exception {
        when(regularisationAccess.mayRead(OWNER, DECIDER)).thenReturn(false);

        mockMvc().perform(get(path)).andExpect(status().isForbidden());
        verify(regularisationAccess).mayRead(OWNER, DECIDER);
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(
                        new AttendanceRegularizationController(service, null),
                        new AttendanceRegularizationControllerWeb(service, null))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
