package org.tornotron.echno_backend.employee;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.tornotron.echno_backend.attendance.mapper.ShiftTimingMapperImpl;
import org.tornotron.echno_backend.common.mapper.AttachmentMapperImpl;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.common.service.KeycloakGroupService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapperImpl;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserContextService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a person may change about themselves through {@code PATCH /employee/{id}}, against a real
 * CockroachDB.
 *
 * <p>The guard on that endpoint, {@code isSelfOrHasAnyOrgRole(#id, 'system-admin', 'hr-admin')},
 * answers whether the caller may touch the record. It was never asked which field, and the field
 * list the service accepts reaches pay, employment status, the organizational identifier and the
 * reporting line. So the self clause, added so that a person could maintain their own contact
 * details from the phone, also let them raise their own salary and name their own manager. See
 * #735.
 *
 * <p><b>Why this is an integration test and not a web slice.</b> A slice over the controller
 * replaces {@code @orgSecurity} with a mock, so it fixes the expression the annotation evaluates
 * and nothing about what the expression means; it also replaces the service, so the field scope
 * never runs at all. Both halves of this question live below the annotation. Here the real
 * {@link OrganizationSecurityService} reads real authorities off a real token, and the real service
 * writes to a real database, so a passing assertion is a statement about the running application
 * rather than about a stub. Nothing here is stubbed to return true for any role: the self-only
 * caller simply holds no role authority, which is the same thing the deployed system would have
 * to be true of them.
 *
 * <p>Every refusal is checked twice: that it was refused, and that the column still holds what it
 * held. A refusal that happens after the write is not a refusal.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({EmployeeService.class, EmployeeHierarchyService.class, OrganizationSecurityService.class,
        UserContextService.class, EmployeeMapperImpl.class, AttachmentMapperImpl.class,
        ShiftTimingMapperImpl.class})
class EmployeePatchSelfScopeIT extends AbstractIntegrationTest {

    private static final double ORIGINAL_SALARY = 42_000.0;

    @Autowired
    private EmployeeService employeeService;

    @PersistenceContext
    private EntityManager em;

    @MockitoBean
    private KeycloakGroupService keycloakGroupService;

    @MockitoBean
    private FileStorageService fileStorageService;

    private Long organizationId;
    private Long subjectId;
    private Long colleagueId;
    private String subjectKeycloakId;
    private String adminKeycloakId;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();

        Organization organization = persistOrganization("Field Scope Org");
        em.flush();
        organizationId = organization.getId();

        Employee subject = persistEmployee(organization, "Ravi Worker", "kc-ravi-worker");
        Employee colleague = persistEmployee(organization, "Priya Colleague", "kc-priya-colleague");
        persistEmployee(organization, "Sam Personnel", "kc-sam-personnel");
        em.flush();

        subjectId = subject.getId();
        colleagueId = colleague.getId();
        subjectKeycloakId = "kc-ravi-worker";
        adminKeycloakId = "kc-sam-personnel";

        TenantContext.setCurrentOrgId(organizationId);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    @DisplayName("A person editing their own record cannot raise their own salary")
    void selfCaller_isRefusedSalary() {
        authenticateAsSelfOnly();

        assertThatThrownBy(() -> employeeService.partialUpdateAnEmployee(
                updates("salary", 250_000.0), subjectId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("salary");

        assertThat(reloadSubject().getSalary()).isEqualTo(ORIGINAL_SALARY);
    }

    @Test
    @DisplayName("A person editing their own record cannot name their own manager")
    void selfCaller_isRefusedManagerId() {
        authenticateAsSelfOnly();

        assertThatThrownBy(() -> employeeService.partialUpdateAnEmployee(
                updates("managerId", colleagueId), subjectId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("managerId");

        assertThat(reloadSubject().getManager()).isNull();
    }

    @Test
    @DisplayName("A person editing their own record cannot change their own employment status")
    void selfCaller_isRefusedStatus() {
        authenticateAsSelfOnly();

        assertThatThrownBy(() -> employeeService.partialUpdateAnEmployee(
                updates("status", EmployeeStatus.inactive.name()), subjectId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("status");

        assertThat(reloadSubject().getStatus()).isEqualTo(EmployeeStatus.active);
    }

    @Test
    @DisplayName("A person editing their own record cannot rewrite their organizational identifier")
    void selfCaller_isRefusedEmployeeId() {
        authenticateAsSelfOnly();

        assertThatThrownBy(() -> employeeService.partialUpdateAnEmployee(
                updates("employeeId", "ECH-9999"), subjectId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("employeeId");

        assertThat(reloadSubject().getEmployeeId()).isEqualTo("ECH-0001");
    }

    @Test
    @DisplayName("A person editing their own record cannot promote themselves or move department")
    void selfCaller_isRefusedDesignationAndDepartment() {
        authenticateAsSelfOnly();

        assertThatThrownBy(() -> employeeService.partialUpdateAnEmployee(
                updates("designation", "Chief Engineer"), subjectId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("designation");

        assertThatThrownBy(() -> employeeService.partialUpdateAnEmployee(
                updates("department", "Finance"), subjectId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("department");

        Employee reloaded = reloadSubject();
        assertThat(reloaded.getDesignation()).isEqualTo("Site Engineer");
        assertThat(reloaded.getDepartment()).isEqualTo("Execution");
    }

    @Test
    @DisplayName("A person editing their own record cannot backdate their own joining date")
    void selfCaller_isRefusedJoiningDate() {
        authenticateAsSelfOnly();

        assertThatThrownBy(() -> employeeService.partialUpdateAnEmployee(
                updates("joiningDate", "2015-01-01T00:00:00"), subjectId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("joiningDate");

        assertThat(reloadSubject().getJoiningDate())
                .isEqualTo(LocalDateTime.of(2024, 6, 1, 0, 0));
    }

    @Test
    @DisplayName("A refusal names every field the caller may not set, not only the first")
    void selfCaller_isToldAboutEveryRefusedField() {
        authenticateAsSelfOnly();

        Map<String, Object> updates = new HashMap<>();
        updates.put("phoneNumber", "+91 90000 00000");
        updates.put("salary", 250_000.0);
        updates.put("managerId", colleagueId);

        assertThatThrownBy(() -> employeeService.partialUpdateAnEmployee(updates, subjectId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("managerId")
                .hasMessageContaining("salary");

        // The whole update is refused, so the field the caller was entitled to change does not
        // land either. A partial application would let the refused fields be probed for one at a
        // time while the permitted ones quietly took effect.
        Employee reloaded = reloadSubject();
        assertThat(reloaded.getPhoneNumber()).isEqualTo("0000000000");
        assertThat(reloaded.getSalary()).isEqualTo(ORIGINAL_SALARY);
    }

    @Test
    @DisplayName("Self-service still works: contact details, name and date of birth")
    void selfCaller_stillMaintainsTheirOwnContactDetails() {
        authenticateAsSelfOnly();

        Map<String, Object> updates = new HashMap<>();
        updates.put("phoneNumber", "+91 90000 11111");
        updates.put("emailAddress", "ravi.worker@echno.test");
        updates.put("employeeName", "Ravi Worker Jr");
        updates.put("dateOfBirth", "1991-02-03T00:00:00");

        assertThatCode(() -> employeeService.partialUpdateAnEmployee(updates, subjectId))
                .doesNotThrowAnyException();

        Employee reloaded = reloadSubject();
        assertThat(reloaded.getPhoneNumber()).isEqualTo("+91 90000 11111");
        assertThat(reloaded.getEmailAddress()).isEqualTo("ravi.worker@echno.test");
        assertThat(reloaded.getEmployeeName()).isEqualTo("Ravi Worker Jr");
        assertThat(reloaded.getDateOfBirth()).isEqualTo(LocalDateTime.of(1991, 2, 3, 0, 0));
    }

    @Test
    @DisplayName("hr-admin still sets pay and the reporting line")
    void personnelRole_stillSetsTheAdministrativeFields() {
        authenticateAsPersonnel("hr-admin");

        Map<String, Object> updates = new HashMap<>();
        updates.put("salary", 55_000.0);
        updates.put("managerId", colleagueId);
        updates.put("designation", "Senior Site Engineer");

        assertThatCode(() -> employeeService.partialUpdateAnEmployee(updates, subjectId))
                .doesNotThrowAnyException();

        Employee reloaded = reloadSubject();
        assertThat(reloaded.getSalary()).isEqualTo(55_000.0);
        assertThat(reloaded.getManager()).isNotNull();
        assertThat(reloaded.getManager().getId()).isEqualTo(colleagueId);
        assertThat(reloaded.getDesignation()).isEqualTo("Senior Site Engineer");
    }

    @Test
    @DisplayName("system-admin still sets pay, so the split is on the role and not on the person")
    void systemAdmin_stillSetsPay() {
        authenticateAsPersonnel("system-admin");

        assertThatCode(() -> employeeService.partialUpdateAnEmployee(
                updates("salary", 61_000.0), subjectId))
                .doesNotThrowAnyException();

        assertThat(reloadSubject().getSalary()).isEqualTo(61_000.0);
    }

    @Test
    @DisplayName("An hr-admin editing their own record is not refused their own pay")
    void personnelRole_editingTheirOwnRecordIsNotRefused() {
        authenticateAsPersonnel("hr-admin");

        Long ownId = em.createQuery(
                        "select e.id from Employee e where e.user.keycloakId = :kc", Long.class)
                .setParameter("kc", adminKeycloakId)
                .getSingleResult();

        assertThatCode(() -> employeeService.partialUpdateAnEmployee(
                updates("salary", 99_000.0), ownId))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A role held in another organization does not unlock the fields here")
    void roleInAnotherOrganization_doesNotUnlockTheFields() {
        // The authority is org-scoped, so a role string minted for a different organization must
        // not satisfy the check for this one. Written because the field scope reads the same
        // authority format the guards do, and a check that dropped the organization id would pass
        // every other test in this class.
        authenticate(subjectKeycloakId,
                List.of(new SimpleGrantedAuthority("ORG_" + (organizationId + 1) + "_ROLE_hr-admin")));

        assertThatThrownBy(() -> employeeService.partialUpdateAnEmployee(
                updates("salary", 250_000.0), subjectId))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(reloadSubject().getSalary()).isEqualTo(ORIGINAL_SALARY);
    }

    @Test
    @DisplayName("A neighbouring role that the PATCH never admitted does not unlock the fields")
    void projectManagerRole_doesNotUnlockTheFields() {
        // project-manager reads the directory and is not named on this PATCH. Isolating it proves
        // the check tests the two roles it names rather than any role at all.
        authenticateAsPersonnel("project-manager");

        assertThatThrownBy(() -> employeeService.partialUpdateAnEmployee(
                updates("salary", 250_000.0), subjectId))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(reloadSubject().getSalary()).isEqualTo(ORIGINAL_SALARY);
    }

    private Map<String, Object> updates(String key, Object value) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(key, value);
        return updates;
    }

    private Employee reloadSubject() {
        em.flush();
        em.clear();
        return em.find(Employee.class, subjectId);
    }

    private void authenticateAsSelfOnly() {
        // Membership and nothing else. This is the caller the self clause on the guard exists for.
        authenticate(subjectKeycloakId,
                List.of(new SimpleGrantedAuthority("ORG_MEMBER_" + organizationId)));
    }

    private void authenticateAsPersonnel(String role) {
        authenticate(adminKeycloakId, List.of(
                new SimpleGrantedAuthority("ORG_MEMBER_" + organizationId),
                new SimpleGrantedAuthority("ORG_" + organizationId + "_ROLE_" + role)));
    }

    private void authenticate(String keycloakId, List<GrantedAuthority> authorities) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(keycloakId)
                .claim("sub", keycloakId)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, authorities, keycloakId));
    }

    private Organization persistOrganization(String name) {
        Organization organization = new Organization();
        organization.setOrganizationName(name);
        organization.setOrganizationAddress(name + " address");
        organization.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        organization.setOrganizationPhone("0000000000");
        em.persist(organization);
        return organization;
    }

    private Employee persistEmployee(Organization organization, String name, String keycloakId) {
        User user = new User();
        user.setKeycloakId(keycloakId);
        user.setName(name);
        em.persist(user);

        Employee employee = new Employee();
        employee.setOrganization(organization);
        employee.setUser(user);
        employee.setEmployeeName(name);
        employee.setEmployeeId("ECH-0001");
        employee.setGender("U");
        employee.setPhoneNumber("0000000000");
        employee.setEmailAddress(name.toLowerCase().replace(" ", ".") + "@emp.test");
        employee.setDateOfBirth(LocalDateTime.of(1990, 1, 1, 0, 0));
        employee.setJoiningDate(LocalDateTime.of(2024, 6, 1, 0, 0));
        employee.setDesignation("Site Engineer");
        employee.setDepartment("Execution");
        employee.setSalary(ORIGINAL_SALARY);
        employee.setStatus(EmployeeStatus.active);
        employee.setOrgRoles(new HashSet<>());
        em.persist(employee);
        return employee;
    }
}
