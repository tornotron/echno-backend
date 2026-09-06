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
 * <p>The measured answer is that the filter does reach the join, so the query cannot resolve an
 * organization that is not the current tenant and answers empty. That makes the lookup a
 * not-found rather than a cross-tenant read, and it makes any endpoint built on it dead across
 * organizations rather than exploitable.
 *
 * <p>It does not make the guard on such an endpoint optional. This query establishes employment,
 * never a role, and the organization it is asked about still arrives from the caller.
 * {@code TenantIsolationIT.findById_onTheTenantRootItself_isNotCoveredByEitherMechanism} makes
 * the same point for the primary-key load, where nothing scopes the tenant root at all; this pins
 * the join-shaped case beside it.
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
    void anotherOrganizationTheCallerIsAlsoEmployedByDoesNotResolveUnderTheFilter() {
        // The measured answer to #698's open question. The filter reaches the explicit
        // JOIN o.employees e: with it pinned to organization A the joined Employee rows are
        // narrowed to A, so nothing satisfies the join for organization B even though the caller
        // genuinely holds an employment record there. The lookup resolves nothing and the caller
        // gets the not-found the service raises.
        //
        // Nothing is thrown on the way. The fail-closed load listener never sees a foreign row,
        // because no foreign row is selected, so the filter is doing this on its own. That makes
        // the cross-organization duplicate path dead rather than exploitable, which is the branch
        // #698 named and could not settle by reading, and it is why the guard repair beside this
        // is a correctness fix rather than the closing of a live hole.
        //
        // Asserted as emptiness rather than as a refusal. An earlier form of this test read the
        // outcome as a TenantAccessDeniedException and said so; splitting the assertion one
        // property per test showed that no throwable is raised at all, so the refusal reading was
        // wrong and the claim is corrected here rather than softened.
        TenantContext.setCurrentOrgId(orgAId);
        enableOrgFilterFor(orgAId);

        assertThat(catchThrowable(() -> organizationRepository.findByIdAndUserEmail(orgBId, EMAIL)))
                .as("the lookup completes rather than being refused by the load listener")
                .isNull();
        assertThat(organizationRepository.findByIdAndUserEmail(orgBId, EMAIL)).isEmpty();
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
