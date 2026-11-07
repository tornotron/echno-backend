package org.tornotron.echno_backend.common.identity.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.tornotron.echno_backend.common.identity.IdentityTokenMapper;
import org.tornotron.echno_backend.common.identity.Principal;
import org.tornotron.echno_backend.common.identity.SignInMethod;
import org.tornotron.echno_backend.common.identity.keycloak.KeycloakTokenMapper;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.modules.access.matrix.AccessFixtures;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/** The read every request makes, on CockroachDB. Each change shows on the very next read. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MembershipAuthoritiesIT extends AbstractIntegrationTest {

    private static final IdentityTokenMapper KEYCLOAK = new KeycloakTokenMapper();

    /** A provider that never wrote the old column. */
    private static final IdentityTokenMapper OTHER_PROVIDER = new IdentityTokenMapper() {
        @Override
        public Principal toPrincipal(Jwt token) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String provider() {
            return "other";
        }

        @Override
        public String issuer() {
            return "https://other.example.test";
        }
    };

    @Autowired
    private TestEntityManager em;

    @Autowired
    private JdbcTemplate jdbc;

    private AccessFixtures fixtures;
    private MembershipAuthorities membership;
    private Organization organization;
    private Employee person;
    private String subject;
    private Principal principal;

    @BeforeEach
    void anEmployeeLinkedToAnAccount() {
        fixtures = new AccessFixtures(em.getEntityManager());
        membership = new MembershipAuthorities(jdbc, KEYCLOAK);
        organization = fixtures.organization("Membership");
        person = fixtures.employee(organization, "membership-person");
        em.flush();
        subject = person.getUser().getKeycloakId();
        jdbc.update("""
                INSERT INTO user_identity (id, provider, issuer, subject, user_id)
                VALUES (gen_random_uuid(), ?, ?, ?, ?)
                """, KEYCLOAK.provider(), KEYCLOAK.issuer(), subject, person.getUser().getId());
        principal = new Principal(KEYCLOAK.issuer(), subject, null, SignInMethod.UNKNOWN);
    }

    private Set<String> held() {
        return membership.read(principal).authorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    private String member() {
        return "ORG_MEMBER_" + organization.getId();
    }

    private String role(String name) {
        return "ORG_" + organization.getId() + "_ROLE_" + name;
    }

    private void addRole(String role) {
        jdbc.update("INSERT INTO employee_org_roles (employee_id, role) VALUES (?, ?)", person.getId(), role);
    }

    private void setStatus(String status) {
        jdbc.update("UPDATE employee SET status = ? WHERE id = ?", status, person.getId());
    }

    @Test
    void anActiveEmployeeHoldsMembershipAndTheirRolesUnderTheSameNames() {
        addRole("SYSTEM_ADMIN");
        addRole("STORE_KEEPER");

        assertThat(held()).containsExactlyInAnyOrder(member(), role("system-admin"), role("store-keeper"));
        assertThat(membership.read(principal).userId()).isEqualTo(person.getUser().getId());
    }

    @Test
    void aRoleChangeShowsOnTheNextRead() {
        addRole("SYSTEM_ADMIN");
        assertThat(held()).contains(role("system-admin"));

        jdbc.update("DELETE FROM employee_org_roles WHERE employee_id = ?", person.getId());

        assertThat(held()).containsExactly(member());
    }

    @Test
    void removalShowsOnTheNextRead() {
        jdbc.update("DELETE FROM employee WHERE id = ?", person.getId());
        em.getEntityManager().detach(person);

        assertThat(held()).isEmpty();
        assertThat(membership.read(principal).userId()).isEqualTo(person.getUser().getId());
    }

    @Test
    void onLeaveAndProbationHoldTheirAccess() {
        addRole("HR_ADMIN");
        for (String status : new String[] {"onLeave", "probation"}) {
            setStatus(status);
            assertThat(held()).as(status).containsExactlyInAnyOrder(member(), role("hr-admin"));
        }
    }

    @Test
    void everyOtherStatusAndNullHoldNothing() {
        addRole("HR_ADMIN");
        for (EmployeeStatus status : new EmployeeStatus[] {EmployeeStatus.inactive, EmployeeStatus.terminated,
                EmployeeStatus.resigned, EmployeeStatus.suspended}) {
            setStatus(status.name());
            assertThat(held()).as(status.name()).isEmpty();
        }
        setStatus(null);
        assertThat(held()).as("null").isEmpty();
    }

    @Test
    void aPersonInTwoOrganizationsHoldsEachOnItsOwn() {
        Organization second = fixtures.organization("Membership Second");
        Employee there = new Employee();
        there.setOrganization(second);
        there.setUser(person.getUser());
        there.setEmployeeName("membership-person");
        there.setGender("U");
        there.setPhoneNumber("0000000000");
        there.setEmailAddress("membership-person@emp.test");
        there.setDateOfBirth(person.getDateOfBirth());
        there.setStatus(EmployeeStatus.resigned);
        em.persistAndFlush(there);
        addRole("HR_ADMIN");

        assertThat(held()).containsExactlyInAnyOrder(member(), role("hr-admin"));

        setStatus("resigned");
        jdbc.update("UPDATE employee SET status = 'active' WHERE id = ?", there.getId());

        assertThat(held()).containsExactly("ORG_MEMBER_" + second.getId());
    }

    @Test
    void aPlatformAdministratorIsMarked() {
        jdbc.update("INSERT INTO platform_admin (user_id) VALUES (?)", person.getUser().getId());

        assertThat(held()).contains(MembershipAuthorities.PLATFORM_ADMIN);
    }

    @Test
    void anAccountWithNoUserHoldsNothing() {
        Principal stranger = new Principal(KEYCLOAK.issuer(), "nobody-has-this", null, SignInMethod.UNKNOWN);

        assertThat(membership.read(stranger).userId()).isNull();
        assertThat(membership.read(stranger).authorities()).isEmpty();
    }

    @Test
    void aUserTheBackfillMissedStillResolvesUnderKeycloak() {
        Employee unlinked = fixtures.employee(organization, "membership-unlinked");
        em.flush();
        Principal old = new Principal(KEYCLOAK.issuer(), unlinked.getUser().getKeycloakId(), null, SignInMethod.UNKNOWN);

        assertThat(membership.read(old).userId()).isEqualTo(unlinked.getUser().getId());
    }

    @Test
    void anotherProviderDoesNotLookInTheOldColumn() {
        Principal old = new Principal(OTHER_PROVIDER.issuer(), subject, null, SignInMethod.UNKNOWN);

        assertThat(new MembershipAuthorities(jdbc, OTHER_PROVIDER).read(old).userId()).isNull();
    }

    @Test
    void oneReadIsOneStatement() {
        JdbcTemplate counting = spy(jdbc);
        addRole("SYSTEM_ADMIN");
        addRole("HR_ADMIN");

        new MembershipAuthorities(counting, KEYCLOAK).read(principal);

        verify(counting, times(1)).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    }
}
