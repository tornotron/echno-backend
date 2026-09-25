package org.tornotron.echno_backend.leave;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberAllocator;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.retry.TransactionalWorkRunner;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.leave.dto.LeaveRequestCreationDto;
import org.tornotron.echno_backend.leave.enums.LeaveStatus;
import org.tornotron.echno_backend.leave.enums.WeekendHolidayTreatment;
import org.tornotron.echno_backend.leave.mapper.LeaveRequestMapper;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Leave request numbering against a real CockroachDB (#858).
 *
 * <p>The staging organization that broke already held {@code LR-<year>-000001} from restored
 * data and had no counter row. The old per-year counter proposed 000001, the insert hit the
 * unique constraint, the rollback took the counter increment with it, and every later attempt
 * proposed 000001 again: no leave request could be created in that organization for the rest
 * of the year. The same number in a second organization also collided, because the constraint
 * spanned every tenant.
 *
 * <p>The test rebuilds that state through the schema the changelog produces and creates two
 * requests through the service, retry template included.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({LeaveRequestService.class, DocumentNumberAllocator.class, TransactionRetryTemplate.class,
        TransactionalWorkRunner.class, SimpleMeterRegistry.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LeaveRequestNumberingIT extends AbstractIntegrationTest {

    @Autowired
    private LeaveRequestService service;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoBean private LeaveApprovalService approvalService;
    @MockitoBean private LeaveRequestValidator leaveRequestValidator;
    @MockitoBean private LeaveRequestMapper leaveRequestMapper;
    @MockitoBean private OrganizationSecurityService orgSecurity;
    @MockitoBean private CurrentEmployeeService currentEmployeeService;

    private record Tenant(Long orgId, Long employeeId, Long policyId) {
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void createsAboveARestoredNumber_andNumbersArePerOrganization() {
        int year = LocalDate.now(ZoneId.of("Asia/Kolkata")).getYear();
        String first = "LR-%d-000001".formatted(year);

        Tenant restored = new TransactionTemplate(txManager).execute(status -> persistTenantHolding(first));
        // The same number in a second tenant: rejected while the constraint spanned every tenant.
        Tenant other = new TransactionTemplate(txManager).execute(status -> persistTenantHolding(first));

        when(orgSecurity.isSelfInCurrentTenant(anyLong())).thenReturn(true);
        when(leaveRequestValidator.charge(any(), any(), any(), any(), any()))
                .thenReturn(new LeaveCharge(2.0, 2.0, 0, WeekendHolidayTreatment.CHARGE_ALL_DAYS));

        TenantContext.setCurrentOrgId(restored.orgId());
        service.createRequest(creationDto(restored.policyId()), restored.employeeId());
        service.createRequest(creationDto(restored.policyId()), restored.employeeId());

        assertThat(numbersOf(restored.orgId())).containsExactlyInAnyOrder(
                first, "LR-%d-000002".formatted(year), "LR-%d-000003".formatted(year));
        assertThat(numbersOf(other.orgId())).containsExactly(first);
    }

    private List<String> numbersOf(Long orgId) {
        return new TransactionTemplate(txManager).execute(status -> entityManager
                .createQuery("SELECT r.requestNumber FROM LeaveRequest r WHERE r.organization.id = :org",
                        String.class)
                .setParameter("org", orgId)
                .getResultList());
    }

    private static LeaveRequestCreationDto creationDto(Long policyId) {
        LeaveRequestCreationDto dto = new LeaveRequestCreationDto();
        dto.setLeavePolicyId(policyId);
        dto.setStartDate(LocalDate.of(2026, 10, 5));
        dto.setEndDate(LocalDate.of(2026, 10, 6));
        dto.setReason("Family function");
        return dto;
    }

    /** One organization with an employee, a policy and a request numbered outside the allocator. */
    private Tenant persistTenantHolding(String requestNumber) {
        String tag = UUID.randomUUID().toString().substring(0, 8);

        Organization org = new Organization();
        org.setOrganizationName("Leave Numbering Org " + tag);
        org.setOrganizationAddress("addr");
        org.setOrganizationEmail("leave-numbering-" + tag + "@example.test");
        org.setOrganizationPhone("0000000000");
        entityManager.persist(org);

        User user = new User();
        user.setKeycloakId("kc-leave-numbering-" + tag);
        user.setName("leave-numbering-" + tag);
        entityManager.persist(user);

        Employee employee = new Employee();
        employee.setOrganization(org);
        employee.setUser(user);
        employee.setEmployeeName("leave-numbering-" + tag);
        employee.setGender("U");
        employee.setPhoneNumber("0000000000");
        employee.setEmailAddress("leave-numbering-" + tag + "@emp.test");
        employee.setDateOfBirth(LocalDateTime.of(1990, 1, 1, 0, 0));
        entityManager.persist(employee);

        LeavePolicy policy = new LeavePolicy();
        policy.setOrganization(org);
        policy.setLeaveTypeCode("AL");
        policy.setLeaveTypeName("Annual Leave");
        policy.setAnnualQuota(20.0);
        policy.setCreatedAt(LocalDateTime.now());
        policy.setUpdatedAt(LocalDateTime.now());
        entityManager.persist(policy);

        LeaveRequest request = new LeaveRequest();
        request.setRequestNumber(requestNumber);
        request.setEmployee(employee);
        request.setOrganization(org);
        request.setLeavePolicy(policy);
        request.setStartDate(LocalDate.of(2026, 9, 1));
        request.setEndDate(LocalDate.of(2026, 9, 2));
        request.setTotalDays(2.0);
        request.setReason("Restored");
        request.setStatus(LeaveStatus.APPROVED);
        request.setCreatedAt(LocalDateTime.now());
        request.setUpdatedAt(LocalDateTime.now());
        entityManager.persist(request);

        entityManager.flush();
        return new Tenant(org.getId(), employee.getId(), policy.getId());
    }
}
