package org.tornotron.echno_backend.modules.toolboxtalks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.modules.toolboxtalks.dto.CreateToolboxTalkRequest;
import org.tornotron.echno_backend.modules.toolboxtalks.dto.ToolboxTalkAttendeeDto;
import org.tornotron.echno_backend.modules.toolboxtalks.dto.ToolboxTalkDto;
import org.tornotron.echno_backend.modules.toolboxtalks.mapper.ToolboxTalksMapperImpl;
import org.tornotron.echno_backend.modules.toolboxtalks.service.ToolboxTalksService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * The module's tenant-isolation proof on the real migration: a talk drafted in one
 * organization reads as absent from another, by id and by list.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ToolboxTalksService.class, ToolboxTalksMapperImpl.class, UserContextService.class, TenantEntityHelper.class})
class ToolboxTalksServiceIT extends AbstractIntegrationTest {

    @Autowired
    private ToolboxTalksService service;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private final String runTag = UUID.randomUUID().toString();
    private Long orgAId;
    private Long orgBId;
    private Long projectAId;
    private Long projectBId;
    private Long conductorAId;
    private Long attendeeAId;
    private Long conductorBId;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        inCommittedTx(() -> {
            Organization orgA = persistOrganization("Toolbox Talks Org A");
            Organization orgB = persistOrganization("Toolbox Talks Org B");
            orgAId = orgA.getId();
            orgBId = orgB.getId();
            projectAId = persistProject(orgA, "Tower A").getId();
            projectBId = persistProject(orgB, "Tower B").getId();
            conductorAId = persistEmployee(orgA, "Supervisor A", EmployeeStatus.active).getId();
            attendeeAId = persistEmployee(orgA, "Mason A", EmployeeStatus.active).getId();
            conductorBId = persistEmployee(orgB, "Supervisor B", EmployeeStatus.active).getId();
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
            deleteForOrgs("DELETE FROM toolbox_talk_attendee WHERE talk_id IN "
                    + "(SELECT id FROM toolbox_talk WHERE organization_id IN (:a,:b))");
            deleteForOrgs("DELETE FROM toolbox_talk WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM employee WHERE organization_id IN (:a,:b)");
            entityManager.createNativeQuery("DELETE FROM users_table WHERE keycloak_id LIKE :tag")
                    .setParameter("tag", "toolbox-talks-it-" + runTag + "-%")
                    .executeUpdate();
            deleteForOrgs("DELETE FROM project WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM organization WHERE id IN (:a,:b)");
        });
    }

    @Test
    void aTalkIsDraftedInTheCallersTenantAndReadBack() {
        ToolboxTalkDto created = service.create(draft(projectAId, conductorAId, List.of(attendeeAId)));

        assertThat(created.id()).isNotNull();
        assertThat(created.topic()).isEqualTo("Working at height");
        assertThat(created.attendees()).extracting(ToolboxTalkAttendeeDto::employeeId).containsExactly(attendeeAId);
        assertThat(service.get(created.id()).notes()).isEqualTo("Harness checks before the scaffold");
        assertThat(service.list(projectAId, null, null, null, 0, 10).getContent())
                .extracting(ToolboxTalkDto::topic).containsExactly("Working at height");
    }

    @Test
    void anotherTenantsTalkReadsAsAbsent() {
        UUID foreign = asTenant(orgBId, () -> service.create(draft(projectBId, conductorBId, List.of())).id());

        assertThatThrownBy(() -> service.get(foreign)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(service.list(null, null, null, null, 0, 10).getContent()).isEmpty();
    }

    private static CreateToolboxTalkRequest draft(Long projectId, Long conductorId, List<Long> attendees) {
        return new CreateToolboxTalkRequest(projectId, null, "Working at height", LocalDate.now(),
                LocalTime.of(7, 30), conductorId, attendees, "Harness checks before the scaffold");
    }

    private <T> T asTenant(Long orgId, Supplier<T> work) {
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
        org.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
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

    private Employee persistEmployee(Organization org, String name, EmployeeStatus status) {
        User user = new User();
        user.setKeycloakId("toolbox-talks-it-" + runTag + "-" + UUID.randomUUID());
        user.setName(name);
        entityManager.persist(user);

        Employee employee = new Employee();
        employee.setOrganization(org);
        employee.setUser(user);
        employee.setEmployeeName(name);
        employee.setEmployeeId("TBT-" + UUID.randomUUID().toString().substring(0, 8));
        employee.setGender("U");
        employee.setPhoneNumber("0000000000");
        employee.setEmailAddress(name.toLowerCase().replace(" ", ".") + "@emp.test");
        employee.setDateOfBirth(LocalDateTime.of(1990, 1, 1, 0, 0));
        employee.setJoiningDate(LocalDateTime.of(2024, 6, 1, 0, 0));
        employee.setDesignation("Site Engineer");
        employee.setDepartment("Execution");
        employee.setStatus(status);
        employee.setOrgRoles(new HashSet<>());
        entityManager.persist(employee);
        entityManager.flush();
        return employee;
    }

    private void enableOrgFilter(Long orgId) {
        entityManager.unwrap(Session.class).enableFilter("orgFilter").setParameter("organizationId", orgId);
    }

    private void disableOrgFilter() {
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
