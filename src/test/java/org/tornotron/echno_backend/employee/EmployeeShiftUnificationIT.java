package org.tornotron.echno_backend.employee;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.tornotron.echno_backend.attendance.ShiftTiming;
import org.tornotron.echno_backend.attendance.mapper.ShiftTimingMapper;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.mapper.AttachmentMapperImpl;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.common.service.KeycloakGroupService;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.employee.dto.EmployeeJoinOrgDto;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapperImpl;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;

import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * End-to-end exercise of the shift unification against a real CockroachDB: joining
 * an organization resolves the structured {@link ShiftTiming} from the supplied id,
 * sets the employee's foreign key, and nests the resolved shift in the response;
 * a shift id from another organization is rejected; and the join path used by an
 * invite (an {@link EmployeeJoinOrgDto} carrying a shiftTimingId) assigns the shift.
 *
 * <p>Keycloak group membership and file storage are external, so they are mocked;
 * everything else runs against the real schema built by Liquibase.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({EmployeeService.class, EmployeeHierarchyService.class, EmployeeMapperImpl.class,
        AttachmentMapperImpl.class, ShiftTimingMapper.class})
class EmployeeShiftUnificationIT extends AbstractIntegrationTest {

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private TestEntityManager em;

    @MockBean
    private KeycloakGroupService keycloakGroupService;

    @MockBean
    private FileStorageService fileStorageService;

    private Long orgAId;
    private Long orgBId;
    private Long shiftAId;
    private Long shiftBId;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        Organization orgA = persistOrganization("Org A");
        Organization orgB = persistOrganization("Org B");
        orgAId = orgA.getId();
        orgBId = orgB.getId();
        shiftAId = persistShift(orgA, "General Shift").getId();
        shiftBId = persistShift(orgB, "Night Shift").getId();
        em.flush();
    }

    @Test
    void join_withValidShiftTimingId_setsForeignKeyAndNestsResolvedShift() {
        User user = persistUser("alice");
        em.flush();

        EmployeeJoinOrgDto dto = joinDto();
        dto.setShiftTimingId(shiftAId);

        EmployeeDto result = employeeService.joinOrganization(user.getId(), orgAId, dto);

        assertThat(result.getShiftTimingId()).isEqualTo(shiftAId);
        assertThat(result.getShiftTiming()).isNotNull();
        assertThat(result.getShiftTiming().getShiftName()).isEqualTo("General Shift");

        Employee persisted = employeeRepository.findById(result.getId()).orElseThrow();
        assertThat(persisted.getShiftTiming()).isNotNull();
        assertThat(persisted.getShiftTiming().getId()).isEqualTo(shiftAId);
    }

    @Test
    void join_withForeignTenantShiftTimingId_isRejected() {
        User user = persistUser("bob");
        em.flush();

        EmployeeJoinOrgDto dto = joinDto();
        // A shift that belongs to Org B cannot be assigned while joining Org A.
        dto.setShiftTimingId(shiftBId);

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> employeeService.joinOrganization(user.getId(), orgAId, dto));
    }

    @Test
    void join_viaInviteCarryingShiftTimingId_assignsThatShift() {
        User user = persistUser("carol");
        em.flush();

        // This is exactly the DTO ProjectInviteCodeService builds from an invite's
        // stored employee details when a user redeems the code.
        EmployeeJoinOrgDto inviteDto = joinDto();
        inviteDto.setShiftTimingId(shiftAId);

        EmployeeDto result = employeeService.joinOrganization(user.getId(), orgAId, inviteDto);

        Employee persisted = employeeRepository.findById(result.getId()).orElseThrow();
        assertThat(persisted.getShiftTiming()).isNotNull();
        assertThat(persisted.getShiftTiming().getId()).isEqualTo(shiftAId);
    }

    @Test
    void join_withoutShiftTimingId_leavesShiftUnassigned() {
        User user = persistUser("dave");
        em.flush();

        EmployeeDto result = employeeService.joinOrganization(user.getId(), orgAId, joinDto());

        assertThat(result.getShiftTimingId()).isNull();
        assertThat(result.getShiftTiming()).isNull();
    }

    private EmployeeJoinOrgDto joinDto() {
        EmployeeJoinOrgDto dto = new EmployeeJoinOrgDto();
        dto.setDesignation("Site Engineer");
        dto.setDepartment("Civil");
        dto.setStatus("active");
        return dto;
    }

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        org.setOrganizationPhone("0000000000");
        em.persist(org);
        return org;
    }

    private ShiftTiming persistShift(Organization org, String name) {
        ShiftTiming shift = ShiftTiming.builder()
                .shiftName(name)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .lunchBreakStart(LocalTime.of(13, 0))
                .lunchBreakEnd(LocalTime.of(13, 30))
                .gracePeriodMinutes(15)
                .minimumWorkHours(java.math.BigDecimal.valueOf(8.0))
                .halfDayWorkHours(java.math.BigDecimal.valueOf(4.0))
                .overtimeThreshold(java.math.BigDecimal.valueOf(9.0))
                .organization(org)
                .build();
        em.persist(shift);
        return shift;
    }

    private User persistUser(String name) {
        User user = new User();
        user.setKeycloakId("kc-" + name);
        user.setName(name);
        user.setGender("U");
        user.setEmail(name + "@emp.test");
        user.setPhone("00000" + name.hashCode());
        user.setDateOfBirth(LocalDateTime.of(1990, 1, 1, 0, 0));
        em.persist(user);
        return user;
    }
}
