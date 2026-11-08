package org.tornotron.echno_backend.modules.access.matrix.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.tornotron.echno_backend.common.configuration.JpaAuditingConfig;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.tornotron.echno_backend.modules.access.matrix.AccessFixtures;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * Each link between an employee, a role, a project or a party and an organization, tried across
 * two organizations at the database, with native SQL so no entity or service stands in the way.
 * Every one is refused by the foreign key named in the test, and each table also takes the same
 * row inside one organization, so the refusal comes from the organization and nothing else.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// Customer carries Spring Data's AuditingEntityListener, so this slice imports the auditing
// configuration for a handler of its own; without it the listener is configured by whichever earlier
// context last enabled auditing, and a persist fails once the context cache has closed that one.
@Import(JpaAuditingConfig.class)
class OrgIntegrityIT extends AbstractIntegrationTest {

    private static final String EMPLOYEE_ROLE = "INSERT INTO access_employee_role "
            + "(id, organization_id, employee_id, role_id, project_id, created_at) "
            + "VALUES (gen_random_uuid(), ?1, ?2, ?3, ?4, now())";
    private static final String EMPLOYEE_GRANT = "INSERT INTO access_employee_grant "
            + "(id, organization_id, employee_id, permission_key, scope, project_id, created_at) "
            + "VALUES (gen_random_uuid(), ?1, ?2, 'finance:read', 'ALL', ?3, now())";
    private static final String GRANTABLE = "INSERT INTO access_role_grantable "
            + "(id, organization_id, role_id, grantable_role_id) VALUES (gen_random_uuid(), ?1, ?2, ?3)";
    private static final String CUSTOMER_LINK = "INSERT INTO access_party_link "
            + "(id, organization_id, employee_id, customer_id, created_at) "
            + "VALUES (gen_random_uuid(), ?1, ?2, ?3, now())";
    private static final String VENDOR_LINK = "INSERT INTO access_party_link "
            + "(id, organization_id, employee_id, vendor_id, created_at) "
            + "VALUES (gen_random_uuid(), ?1, ?2, ?3, now())";

    @Autowired
    private TestEntityManager em;

    private long org;
    private long employee;
    private UUID role;
    private UUID secondRole;
    private long project;
    private UUID customer;
    private long vendor;

    private long strangerEmployee;
    private UUID strangerRole;
    private long strangerProject;
    private UUID strangerCustomer;
    private long strangerVendor;

    @BeforeEach
    void twoOrganizations() {
        AccessFixtures fixtures = new AccessFixtures(em.getEntityManager());
        Organization mine = fixtures.organization("Integrity Mine");
        Organization theirs = fixtures.organization("Integrity Theirs");
        org = mine.getId();
        employee = fixtures.employee(mine, "integrity-mine").getId();
        AccessRole mineRole = fixtures.role(mine, "site-engineer");
        role = mineRole.getId();
        secondRole = fixtures.role(mine, "store-keeper").getId();
        project = fixtures.project(mine, "Mine Tower").getId();
        customer = fixtures.customer(mine, "M-1").getId();
        vendor = fixtures.vendor(mine, "Mine Vendor").getId();

        strangerEmployee = fixtures.employee(theirs, "integrity-theirs").getId();
        strangerRole = fixtures.role(theirs, "site-engineer").getId();
        strangerProject = fixtures.project(theirs, "Their Tower").getId();
        strangerCustomer = fixtures.customer(theirs, "T-1").getId();
        strangerVendor = fixtures.vendor(theirs, "Their Vendor").getId();
        em.flush();
    }

    private int insert(String sql, Object... parameters) {
        var query = em.getEntityManager().createNativeQuery(sql);
        for (int i = 0; i < parameters.length; i++) {
            query.setParameter(i + 1, parameters[i]);
        }
        return query.executeUpdate();
    }

    @Test
    void everyLinkIsAcceptedInsideOneOrganization() {
        assertThat(insert(EMPLOYEE_ROLE, org, employee, role, project)).isEqualTo(1);
        assertThat(insert(EMPLOYEE_ROLE, org, employee, role, null)).isEqualTo(1);
        assertThat(insert(EMPLOYEE_GRANT, org, employee, project)).isEqualTo(1);
        assertThat(insert(GRANTABLE, org, role, secondRole)).isEqualTo(1);
        assertThat(insert(CUSTOMER_LINK, org, employee, customer)).isEqualTo(1);
        assertThat(insert(VENDOR_LINK, org, employee, vendor)).isEqualTo(1);
    }

    @Test
    void anAssignmentRefusesAnEmployeeOfAnotherOrganization() {
        assertThatThrownBy(() -> insert(EMPLOYEE_ROLE, org, strangerEmployee, role, null))
                .hasStackTraceContaining("fk_access_employee_role_employee");
    }

    @Test
    void anAssignmentRefusesARoleOfAnotherOrganization() {
        assertThatThrownBy(() -> insert(EMPLOYEE_ROLE, org, employee, strangerRole, null))
                .hasStackTraceContaining("fk_access_employee_role_role");
    }

    @Test
    void anAssignmentRefusesAProjectOfAnotherOrganization() {
        assertThatThrownBy(() -> insert(EMPLOYEE_ROLE, org, employee, role, strangerProject))
                .hasStackTraceContaining("fk_access_employee_role_project");
    }

    @Test
    void aDirectGrantRefusesAnEmployeeOfAnotherOrganization() {
        assertThatThrownBy(() -> insert(EMPLOYEE_GRANT, org, strangerEmployee, null))
                .hasStackTraceContaining("fk_access_employee_grant_employee");
    }

    @Test
    void aDirectGrantRefusesAProjectOfAnotherOrganization() {
        assertThatThrownBy(() -> insert(EMPLOYEE_GRANT, org, employee, strangerProject))
                .hasStackTraceContaining("fk_access_employee_grant_project");
    }

    @Test
    void aGrantableRowRefusesARoleOfAnotherOrganization() {
        assertThatThrownBy(() -> insert(GRANTABLE, org, strangerRole, role))
                .hasStackTraceContaining("fk_access_role_grantable_role");
    }

    @Test
    void aGrantableRowRefusesAGrantableRoleOfAnotherOrganization() {
        assertThatThrownBy(() -> insert(GRANTABLE, org, role, strangerRole))
                .hasStackTraceContaining("fk_access_role_grantable_grantable");
    }

    @Test
    void aPartyLinkRefusesAnEmployeeOfAnotherOrganization() {
        assertThatThrownBy(() -> insert(CUSTOMER_LINK, org, strangerEmployee, customer))
                .hasStackTraceContaining("fk_access_party_link_employee");
    }

    @Test
    void aPartyLinkRefusesACustomerOfAnotherOrganization() {
        assertThatThrownBy(() -> insert(CUSTOMER_LINK, org, employee, strangerCustomer))
                .hasStackTraceContaining("fk_access_party_link_customer");
    }

    @Test
    void aPartyLinkRefusesAVendorOfAnotherOrganization() {
        assertThatThrownBy(() -> insert(VENDOR_LINK, org, employee, strangerVendor))
                .hasStackTraceContaining("fk_access_party_link_vendor");
    }
}
