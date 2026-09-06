package org.tornotron.echno_backend.organization;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.tornotron.echno_backend.common.exception.TenantAccessDeniedException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantIsolationListenerRegistrar;
import org.tornotron.echno_backend.common.multitenancy.UnscopedAccessGuard;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Whether the Hibernate {@code orgFilter} reaches an entity joined explicitly in HQL, measured
 * against a real database rather than reasoned about.
 *
 * <p>{@code OrganizationRepository.findByIdAndUserEmail} answers "is this caller employed by that
 * organization" with {@code SELECT o FROM Organization o JOIN o.employees e JOIN e.user u}.
 * {@code Organization} is the tenant root and carries no filter; {@code Employee} carries
 * {@code orgFilter}. Whether the filter narrows the joined {@code Employee} decides what the
 * query means for an organization that is not the current tenant, and therefore whether an
 * endpoint resolving a caller-named organization through it is exploitable or merely dead. That
 * question was raised in #698 and could not be settled by reading.
 *
 * <p>The measured answer is that the filter does not reach the join. What refuses the foreign
 * organization is the fail-closed load listener, on the post-load of the joined {@code Employee}.
 * So the lookup is not a silent cross-tenant read, and equally it is not scoped by the mechanism
 * that looks like it should scope it. Either way the guard on such an endpoint has to establish
 * entitlement itself, which is the point
 * {@code TenantIsolationIT.findById_onTheTenantRootItself_isNotCoveredByEitherMechanism} makes for
 * the primary-key load. This pins the join-shaped case beside it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TenantIsolationListenerRegistrar.class, UnscopedAccessGuard.class, SimpleMeterRegistry.class})
class OrganizationLookupUnderTheOrgFilterIT extends AbstractIntegrationTest {

    private static final String EMAIL = "both.orgs@example.test";

    @Autowired
    private OrganizationRepository organizationRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private Long orgAId;
    private Long orgBId;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        Organization orgA = persistOrganization("Org A");
        Organization orgB = persistOrganization("Org B");

        User user = new User();
        user.setName("Someone Employed Twice");
        user.setEmail(EMAIL);
        user.setKeycloakId("keycloak-" + EMAIL);
        entityManager.persist(user);

        persistEmployee(orgA, user);
        persistEmployee(orgB, user);

        entityManager.flush();
        orgAId = orgA.getId();
        orgBId = orgB.getId();
        entityManager.clear();
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** What HibernateFilterConfig does at a transaction boundary, done by hand in this slice. */
    private void enableOrgFilterFor(Long organizationId) {
        entityManager.unwrap(Session.class)
                .enableFilter("orgFilter")
                .setParameter("organizationId", organizationId);
    }

    @Test
    void theCurrentTenantResolvesThroughTheEmployeeJoin() {
        TenantContext.setCurrentOrgId(orgAId);
        enableOrgFilterFor(orgAId);

        assertThat(organizationRepository.findByIdAndUserEmail(orgAId, EMAIL)).isPresent();
    }

    @Test
    void anotherOrganizationIsStoppedByTheLoadListenerRatherThanByTheFilter() {
        // The measured answer to #698's open question, and it is not the one either candidate
        // reading predicted. The org filter does NOT narrow an entity joined explicitly in HQL:
        // the join still matches the caller's employment row in organization B, so the query is
        // satisfiable and the row is loaded. What stops it is the other mechanism.
        // TenantIsolationLoadListener runs on the post-load of that row, which is an Employee,
        // the only tenant-scoped entity this query touches, and refuses it under a request
        // scoped to organization A.
        //
        // Two consequences worth keeping. The lookup is not a silent cross-tenant read, so an
        // endpoint resolving a caller-named organization through it fails loudly rather than
        // succeeding quietly. And the filter cannot be relied on to scope a join, so the
        // fail-closed listener is doing the work here on its own, with no defence behind it.
        TenantContext.setCurrentOrgId(orgAId);
        enableOrgFilterFor(orgAId);

        assertThatThrownBy(() -> organizationRepository.findByIdAndUserEmail(orgBId, EMAIL))
                .isInstanceOf(TenantAccessDeniedException.class)
                .hasMessageContaining("Cross-tenant access denied")
                .hasMessageContaining("the request is scoped to organization " + orgAId);
    }

    @Test
    void withNoFilterEnabledTheSameLookupResolvesEitherOrganization() {
        // The control. Without the filter the query is purely "is this caller employed there",
        // which is what the endpoint's guard was leaning on and what makes the role check on the
        // caller's own tenant the wrong question to ask about the target.
        TenantContext.declareUnscoped("measuring the unfiltered shape of the same query");

        assertThat(organizationRepository.findByIdAndUserEmail(orgAId, EMAIL)).isPresent();
        assertThat(organizationRepository.findByIdAndUserEmail(orgBId, EMAIL)).isPresent();
    }

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        org.setOrganizationPhone("0000000000");
        entityManager.persist(org);
        return org;
    }

    private void persistEmployee(Organization organization, User user) {
        Employee employee = new Employee();
        employee.setOrganization(organization);
        employee.setUser(user);
        employee.setEmployeeName(user.getName());
        employee.setEmailAddress(user.getEmail());
        employee.setGender("unspecified");
        employee.setPhoneNumber("0000000000");
        employee.setDateOfBirth(LocalDateTime.of(1990, 1, 1, 0, 0));
        entityManager.persist(employee);
    }
}
