package org.tornotron.echno_backend.wbs;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.mapper.AttachmentMapper;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.modules.workprogress.time.WorkProgressClock;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.subcontract.SubContract;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.wbs.dto.WbsElementCreationDto;
import org.tornotron.echno_backend.wbs.dto.WbsElementDto;

/**
 * Shared seed for the schedule and progress-inspection ITs on the real migration: two
 * organizations, a project each, an active employee each and a sub-contract each, committed so
 * the tenant filter sees real rows, and removed after each test. Everything a test creates on top
 * runs inside its own rolled-back transaction.
 *
 * <p>Both clocks are pinned to 20:00 UTC on the 18th, which is already the 19th at the sites, so
 * every "today" rule is tested in the window where the UTC date is a day behind.
 */
public abstract class ScheduleIntegrationSupport extends AbstractIntegrationTest {

    protected static final Instant NOW = Instant.parse("2026-09-18T20:00:00Z");
    protected static final LocalDate TODAY = LocalDate.of(2026, 9, 19);

    @TestConfiguration
    public static class FixedClocks {
        @Bean
        @WbsScheduleClock
        Clock wbsScheduleClock() {
            return Clock.fixed(NOW, ZoneId.of("Asia/Kolkata"));
        }

        @Bean
        @WorkProgressClock
        Clock workProgressClock() {
            return Clock.fixed(NOW, ZoneId.of("Asia/Kolkata"));
        }
    }

    // The employee mapper in the WBS mapper chain needs these; no test here touches storage.
    @MockitoBean
    protected AttachmentService attachmentService;

    @MockitoBean
    protected AttachmentMapper attachmentMapper;

    @PersistenceContext
    protected EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    protected WbsElementService wbs;

    private final String runTag = UUID.randomUUID().toString();
    protected Long orgAId;
    protected Long orgBId;
    protected Long projectAId;
    protected Long projectA2Id;
    protected Long projectBId;
    protected Long employeeAId;
    protected Long employeeBId;
    protected Long subContractAId;
    protected Long subContractBId;

    @BeforeEach
    void seedTwoTenants() {
        TenantContext.clear();
        inCommittedTx(() -> {
            Organization orgA = persistOrganization("Schedule Org A " + runTag.substring(0, 8));
            Organization orgB = persistOrganization("Schedule Org B " + runTag.substring(0, 8));
            orgAId = orgA.getId();
            orgBId = orgB.getId();
            projectAId = persistProject(orgA, "Riverside Tower A").getId();
            projectA2Id = persistProject(orgA, "Riverside Tower A2").getId();
            projectBId = persistProject(orgB, "Hillside Tower B").getId();
            employeeAId = persistEmployee(orgA, "Ravi Kumar").getId();
            employeeBId = persistEmployee(orgB, "Other Org Engineer").getId();
            subContractAId = persistSubContract(orgA, projectAId, "Sree Builders").getId();
            subContractBId = persistSubContract(orgB, projectBId, "Other Contractor").getId();
        });
        TenantContext.setCurrentOrgId(orgAId);
        enableOrgFilter(orgAId);
    }

    @AfterEach
    void clearTenantState() {
        disableOrgFilter();
        TenantContext.clear();
    }

    @AfterTransaction
    void removeCommittedRows() {
        if (orgAId == null && orgBId == null) {
            return;
        }
        inCommittedTx(() -> {
            deleteForOrgs("DELETE FROM work_progress_inspection WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM wbs_dependency WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM wbs_element WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM sub_contract WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM employee WHERE organization_id IN (:a,:b)");
            entityManager.createNativeQuery("DELETE FROM users_table WHERE keycloak_id LIKE :tag")
                    .setParameter("tag", "schedule-it-" + runTag + "-%")
                    .executeUpdate();
            deleteForOrgs("DELETE FROM project WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM organization WHERE id IN (:a,:b)");
        });
    }

    // ---------------------------------------------------------------- helpers

    protected WbsElementDto activity(Long projectId, String code, Long parentId, LocalDate start, LocalDate end) {
        WbsElementCreationDto dto = new WbsElementCreationDto();
        dto.setWbsCode(code);
        dto.setTitle("Activity " + code);
        dto.setParentId(parentId);
        dto.setStartDate(start);
        dto.setEndDate(end);
        return wbs.createWbsElement(projectId, dto);
    }

    protected <T> T asTenant(Long orgId, Supplier<T> work) {
        Long previous = TenantContext.getCurrentOrgId();
        disableOrgFilter();
        TenantContext.setCurrentOrgId(orgId);
        enableOrgFilter(orgId);
        try {
            return work.get();
        } finally {
            disableOrgFilter();
            TenantContext.setCurrentOrgId(previous);
            enableOrgFilter(previous);
        }
    }

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(UUID.randomUUID().toString().substring(0, 12) + "@example.test");
        org.setOrganizationPhone("0000000000");
        entityManager.persist(org);
        entityManager.flush();
        return org;
    }

    private Project persistProject(Organization org, String name) {
        Project project = new Project();
        project.setProjectName(name);
        project.setOrganization(org);
        entityManager.persist(project);
        entityManager.flush();
        return project;
    }

    private Employee persistEmployee(Organization org, String name) {
        User user = new User();
        user.setKeycloakId("schedule-it-" + runTag + "-" + UUID.randomUUID());
        user.setName(name);
        entityManager.persist(user);

        Employee employee = new Employee();
        employee.setOrganization(org);
        employee.setUser(user);
        employee.setEmployeeName(name);
        employee.setEmployeeId("WPI-" + UUID.randomUUID().toString().substring(0, 8));
        employee.setGender("U");
        employee.setPhoneNumber("0000000000");
        employee.setEmailAddress(UUID.randomUUID().toString().substring(0, 12) + "@emp.test");
        employee.setDateOfBirth(LocalDateTime.of(1990, 1, 1, 0, 0));
        employee.setJoiningDate(LocalDateTime.of(2024, 6, 1, 0, 0));
        employee.setDesignation("Site Engineer");
        employee.setDepartment("Execution");
        employee.setStatus(EmployeeStatus.active);
        employee.setOrgRoles(new HashSet<>());
        entityManager.persist(employee);
        entityManager.flush();
        return employee;
    }

    private SubContract persistSubContract(Organization org, Long projectId, String contractor) {
        SubContract subContract = new SubContract();
        subContract.setOrganization(org);
        subContract.setProjectId(projectId);
        subContract.setContractName(contractor + " civil works");
        subContract.setContractorName(contractor);
        subContract.setContractValue(BigDecimal.valueOf(2500000));
        entityManager.persist(subContract);
        entityManager.flush();
        return subContract;
    }

    protected void enableOrgFilter(Long orgId) {
        if (orgId != null) {
            entityManager.unwrap(Session.class).enableFilter("orgFilter").setParameter("organizationId", orgId);
        }
    }

    protected void disableOrgFilter() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
    }

    private void deleteForOrgs(String sql) {
        entityManager.createNativeQuery(sql)
                .setParameter("a", orgAId)
                .setParameter("b", orgBId)
                .executeUpdate();
    }

    private void inCommittedTx(Runnable work) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.executeWithoutResult(status -> work.run());
    }
}
