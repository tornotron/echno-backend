package org.tornotron.echno_backend.leave;

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
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantIsolationListenerRegistrar;
import org.tornotron.echno_backend.common.multitenancy.UnscopedAccessGuard;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.leave.mapper.LeavePolicyMapper;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserContextService;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The uniqueness rule on the cross-organization duplicate path, measured against a real database
 * rather than against a mock of the check it is asking about.
 *
 * <p>{@code LeavePolicyService.duplicatePolicy} means to refuse a copy whose leave-type code the
 * target organization already holds, and says so with a 409 naming the code. It asked
 * {@code existsByOrganizationIdAndLeaveTypeCode(targetOrganizationId, code)} against
 * {@code LeavePolicy}, which carries {@code orgFilter} as a query root. Under a request scoped to
 * one organization the filter's predicate and the argument then name two different organizations,
 * the query can match no row whatever the table holds, and the rule is skipped on exactly the path
 * that needs it. What the caller gets instead is whatever the unique constraint does to the
 * insert, which is a 500.
 *
 * <p>A mocked repository cannot show any of that: the service calls the method it calls either
 * way, and a stub answers the question the filter would have refused to. So this runs the real
 * repository against the real schema, with the filter enabled by hand the way
 * {@link org.tornotron.echno_backend.organization.OrganizationLookupUnderTheOrgFilterIT} does, and
 * asserts on what comes out of the service.
 *
 * <p>The service is assembled by hand rather than through the application context. What is under
 * test is one method's use of two repositories, and a {@code @DataJpaTest} slice already has both
 * of those wired to the database; the rest of the service's collaborators do not participate.
 *
 * <p>Assertions are one per test and none of them stringifies an entity. {@code Organization} is a
 * Lombok {@code @Data} entity whose generated {@code toString} walks its lazy {@code employees}
 * collection, so an AssertJ failure description built around one loads a foreign organization's
 * employees under the current tenant and raises {@code TenantAccessDeniedException} from inside the
 * error message, which then reads as the result being reported on. See #718.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TenantIsolationListenerRegistrar.class, UnscopedAccessGuard.class, SimpleMeterRegistry.class})
class LeavePolicyDuplicateAcrossOrganizationsIT extends AbstractIntegrationTest {

    private static final String EMAIL = "hr.admin.both@example.test";
    private static final String CODE = "SICK";

    @Autowired
    private LeavePolicyRepository policyRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private LeavePolicyService service;

    private Long sourceOrgId;
    private Long targetOrgId;
    private Long sourcePolicyId;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        // The filter is session state and each test says for itself what it wants, so start from
        // off rather than from whatever ran before.
        entityManager.unwrap(Session.class).disableFilter("orgFilter");

        Organization source = persistOrganization("Duplicate Source Org");
        Organization target = persistOrganization("Duplicate Target Org");

        User user = new User();
        user.setName("Admin In Both");
        user.setEmail(EMAIL);
        user.setKeycloakId("keycloak-" + EMAIL);
        entityManager.persist(user);

        persistEmployee(source, user);
        persistEmployee(target, user);

        LeavePolicy sourcePolicy = persistPolicy(source, CODE, "Sick Leave");
        // The collision. The target already holds the same leave-type code, which is precisely
        // what the duplicate check exists to refuse.
        persistPolicy(target, CODE, "Sick Leave");

        entityManager.flush();
        sourceOrgId = source.getId();
        targetOrgId = target.getId();
        sourcePolicyId = sourcePolicy.getId();
        entityManager.clear();

        UserContextService userContextService = mock(UserContextService.class);
        when(userContextService.getCurrentUserEmail()).thenReturn(EMAIL);
        service = new LeavePolicyService(policyRepository, organizationRepository,
                employeeRepository, userContextService, mock(LeavePolicyMapper.class));
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
    void aCodeTheTargetAlreadyHoldsIsRefusedRatherThanLeftToTheUniqueConstraint() {
        TenantContext.setCurrentOrgId(sourceOrgId);
        enableOrgFilterFor(sourceOrgId);

        assertThatThrownBy(() -> {
            service.duplicatePolicy(sourcePolicyId, targetOrgId);
            entityManager.flush();
        }).isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void theFilteredExistenceCheckCannotSeeTheRowThatCollides() {
        // Why the refusal above has to be asked for differently. The row is in the table and the
        // arguments name it exactly; the filter's own predicate is what makes the query
        // unsatisfiable, because it names the organization the request is scoped to instead.
        TenantContext.setCurrentOrgId(sourceOrgId);
        enableOrgFilterFor(sourceOrgId);

        assertThat(policyRepository.existsByOrganizationIdAndLeaveTypeCode(targetOrgId, CODE))
                .as("a filtered query root cannot answer for an organization other than the tenant")
                .isFalse();
    }

    @Test
    void withNoFilterEnabledTheSameCheckFindsIt() {
        // The control. The row is there and the check is right about everything except the one
        // thing it cannot see.
        TenantContext.declareUnscoped("measuring the unfiltered shape of the same check");

        assertThat(policyRepository.existsByOrganizationIdAndLeaveTypeCode(targetOrgId, CODE))
                .as("the collision is real; only the filter was hiding it")
                .isTrue();
    }

    @Test
    void aCodeTheTargetDoesNotHoldStillDuplicates() {
        // The other half of the rule: refusing everything would be as wrong as refusing nothing,
        // and naming the current tenant as the target is what would produce that.
        TenantContext.setCurrentOrgId(sourceOrgId);
        enableOrgFilterFor(sourceOrgId);

        LeavePolicy onlyInTheSource = persistPolicy(
                entityManager.getReference(Organization.class, sourceOrgId), "CASUAL", "Casual Leave");
        entityManager.flush();

        assertThat(catchThrowable(() -> service.duplicatePolicy(onlyInTheSource.getId(), targetOrgId)))
                .as("a code the target does not hold is copied rather than refused")
                .isNull();
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

    private LeavePolicy persistPolicy(Organization organization, String code, String name) {
        LeavePolicy policy = new LeavePolicy();
        policy.setOrganization(organization);
        policy.setLeaveTypeCode(code);
        policy.setLeaveTypeName(name);
        policy.setAnnualQuota(12.0);
        policy.setIsActive(true);
        entityManager.persist(policy);
        return policy;
    }
}
