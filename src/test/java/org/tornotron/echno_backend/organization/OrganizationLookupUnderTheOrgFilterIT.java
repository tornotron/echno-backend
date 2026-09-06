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
 * <p>Either way the guard on such an endpoint has to establish entitlement itself, which is the
 * point {@code TenantIsolationIT.findById_onTheTenantRootItself_isNotCoveredByEitherMechanism}
 * makes for the primary-key load. This pins the join-shaped case beside it.
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
    void anotherOrganizationTheCallerIsAlsoEmployedByDoesNotResolveUnderTheFilter() {
        // The measured answer to #698's open question. With the filter pinned to organization A,
        // the joined Employee rows are narrowed to A, so no row satisfies the join for
        // organization B even though the caller genuinely has an employment record there. The
        // cross-organization duplicate path is therefore dead rather than exploitable: it
        // resolves nothing and raises a not-found. That is what keeps the guard repair a
        // correctness fix rather than a live-vulnerability fix, and it is also why moving the
        // role check to the target cannot be the whole answer on its own.
        TenantContext.setCurrentOrgId(orgAId);
        enableOrgFilterFor(orgAId);

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
