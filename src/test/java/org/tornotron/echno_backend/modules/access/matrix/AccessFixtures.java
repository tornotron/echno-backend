package org.tornotron.echno_backend.modules.access.matrix;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.finance.ledger.domain.Customer;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessEmployeeGrant;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessEmployeeRole;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.vendor.Vendor;
import org.tornotron.echno_backend.vendor.enums.VendorStatus;
import org.tornotron.echno_backend.vendor.enums.VendorType;

/** The rows the access tests stand on, persisted through whichever entity manager a test has. */
public final class AccessFixtures {

    private final EntityManager em;

    public AccessFixtures(EntityManager em) {
        this.em = em;
    }

    public Organization organization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        org.setOrganizationPhone("0000000000");
        em.persist(org);
        return org;
    }

    public Employee employee(Organization org, String name) {
        return employee(org, name, EmployeeStatus.active);
    }

    public Employee employee(Organization org, String name, EmployeeStatus status) {
        User user = new User();
        user.setKeycloakId("kc-" + name);
        user.setName(name);
        em.persist(user);
        Employee employee = new Employee();
        employee.setOrganization(org);
        employee.setUser(user);
        employee.setEmployeeName(name);
        employee.setGender("U");
        employee.setPhoneNumber("0000000000");
        employee.setEmailAddress(name + "@emp.test");
        employee.setDateOfBirth(LocalDateTime.of(1990, 1, 1, 0, 0));
        employee.setStatus(status);
        em.persist(employee);
        return employee;
    }

    public Project project(Organization org, String name) {
        Project project = new Project();
        project.setOrganization(org);
        project.setProjectName(name);
        project.setEmployees(new ArrayList<>());
        em.persist(project);
        return project;
    }

    public Customer customer(Organization org, String code) {
        Customer customer = new Customer();
        customer.setCode(code);
        customer.setName("Customer " + code);
        customer.setActive(true);
        customer.setOrganization(org);
        // A JPA slice runs without auditing, which normally stamps the finance base entity.
        customer.setCreatedAt(Instant.now());
        customer.setUpdatedAt(Instant.now());
        em.persist(customer);
        return customer;
    }

    public Vendor vendor(Organization org, String name) {
        Vendor vendor = new Vendor();
        vendor.setVendorName(name);
        vendor.setType(VendorType.MATERIALS);
        vendor.setStatus(VendorStatus.ACTIVE);
        vendor.setVendorEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        vendor.setOrganization(org);
        em.persist(vendor);
        return vendor;
    }

    /** A role not yet persisted, so a test can add grants first. */
    public AccessRole newRole(Organization org, String slug) {
        AccessRole role = new AccessRole();
        role.setOrganization(org);
        role.setSlug(slug);
        role.setName(slug);
        return role;
    }

    public AccessRole role(Organization org, String slug) {
        AccessRole role = newRole(org, slug);
        em.persist(role);
        return role;
    }

    public AccessEmployeeRole assign(Organization org, Employee person, AccessRole role, Long projectId) {
        AccessEmployeeRole assignment = new AccessEmployeeRole();
        assignment.setOrganization(org);
        assignment.setEmployeeId(person.getId());
        assignment.setRole(role);
        assignment.setProjectId(projectId);
        em.persist(assignment);
        return assignment;
    }

    public AccessEmployeeGrant grant(Organization org, Employee person, String key, AccessScope scope, Long projectId) {
        AccessEmployeeGrant grant = new AccessEmployeeGrant();
        grant.setOrganization(org);
        grant.setEmployeeId(person.getId());
        grant.setPermissionKey(key);
        grant.setScope(scope);
        grant.setProjectId(projectId);
        em.persist(grant);
        return grant;
    }
}
