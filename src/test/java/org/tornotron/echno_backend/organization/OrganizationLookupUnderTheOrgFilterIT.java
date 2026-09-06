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
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantIsolationListenerRegistrar;
import org.tornotron.echno_backend.common.multitenancy.UnscopedAccessGuard;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

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
 * <p>The measured answer is that it does not. The filter narrows a query root and a filtered
 * collection; it leaves an entity joined explicitly in HQL alone. So the query resolves any
 * organization the caller holds an {@code Employee} row in, whatever tenant the request is scoped
 * to, and it does so silently: the joined {@code Employee} is never selected, so the fail-closed
 * load listener gets no post-load to judge either. Neither mechanism scopes this query.
 *
 * <p>An endpoint that resolves a caller-named organization through it therefore reads across a
 * tenant and learns only that the caller is employed there, which is why such an endpoint has to
 * establish entitlement in its own guard. That is the same conclusion
 * {@code TenantIsolationIT.findById_onTheTenantRootItself_isNotCoveredByEitherMechanism} reaches
 * for the primary-key load, by a different route; this pins the join-shaped case beside it.
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
        // The filter is session state and each test says for itself whether it wants it on, so
        // start from off rather than from whatever the previous method left. Without this the
        // outcome of a test here depends on the order JUnit happens to run them in.
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
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
    void anotherOrganizationTheCallerIsAlsoEmployedByResolvesRightThroughTheFilter() {
        // The measured answer to #698's open question, and it is the worse of the two.
        //
        // The filter does NOT reach an entity joined explicitly in HQL. Pinned to organization A
        // it leaves JOIN o.employees e unnarrowed, the caller's employment row in organization B
        // still satisfies the join, and the query hands back organization B. Nothing is thrown on
        // the way: the joined Employee is never selected, so the fail-closed load listener gets no
        // post-load to judge and never sees the row. Neither mechanism scopes this query.
        //
        // So an endpoint that resolves a caller-named organization through findByIdAndUserEmail
        // reads across a tenant, silently, and establishes only that the caller is employed there.
        // That is why the guard on LeavePolicyController.duplicatePolicy has to answer for the
        // target organization itself, and why doing so closes a live path rather than tidying a
        // dead one.
        //
        // Read the assertions in this order deliberately: no throwable first, then the identity of
        // what came back. Two earlier forms of this test asserted isEmpty() and were told that a
        // TenantAccessDeniedException came out of the lookup, which read as the listener refusing
        // the row. It was not. Organization is a Lombok @Data entity whose generated toString
        // walks its lazy employees collection, so AssertJ building the failure description for a
        // present Optional initialized that collection, loaded organization B's Employee rows
        // under tenant A, and tripped the listener there. The exception came from the assertion's
        // own error message and hid the result it was reporting on. Nothing here calls toString on
        // the entity for that reason.
        TenantContext.setCurrentOrgId(orgAId);
        enableOrgFilterFor(orgAId);

        assertThat(catchThrowable(() -> organizationRepository.findByIdAndUserEmail(orgBId, EMAIL)))
                .as("the lookup completes rather than being refused")
                .isNull();

        Optional<Organization> resolved = organizationRepository.findByIdAndUserEmail(orgBId, EMAIL);
        assertThat(resolved).as("a foreign organization resolves under an active tenant").isPresent();
        assertThat(resolved.get().getId())
                .as("and it is the foreign one, not the current tenant")
                .isEqualTo(orgBId);
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
