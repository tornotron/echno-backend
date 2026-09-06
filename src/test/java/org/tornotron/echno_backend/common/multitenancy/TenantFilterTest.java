package org.tornotron.echno_backend.common.multitenancy;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The request-side half of #507. Every request now leaves the filter having declared what tenant
 * scope it runs under, including the ones that run under none.
 *
 * <p>Before this, the pre-tenant paths were listed in {@code shouldNotFilter}, which meant the
 * filter never ran and the request reached the database with a scope indistinguishable from a
 * request that had lost its organization by accident. {@code GET /user/web} is the one that
 * mattered: it materializes {@code Attachment}, which is tenant-scoped.
 *
 * <p>The behaviour of those requests is otherwise unchanged, which these assertions pin: no
 * organization id is set, no membership check runs, and no bypass is granted. Only the
 * declaration is new.
 */
class TenantFilterTest {

    private static final String ORG_MEMBER_7 = "ORG_MEMBER_7";

    private TenantFilter filter;
    private MockHttpServletResponse response;

    /** What the tenant scope looked like at the moment the request reached the application. */
    private record ScopeAtChain(Long orgId, boolean bypassed, String unscopedReason) {}

    private ScopeAtChain observed;

    @BeforeEach
    void setUp() {
        filter = new TenantFilter();
        ReflectionTestUtils.setField(filter, "backendVersion", "v1");
        response = new MockHttpServletResponse();
        observed = null;
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private FilterChain capturingChain() {
        return (req, res) -> observed = new ScopeAtChain(
                TenantContext.getCurrentOrgId(),
                TenantContext.isBypassed(),
                TenantContext.getUnscopedReason());
    }

    private void authenticateWith(String... authorities) {
        TestingAuthenticationToken token = new TestingAuthenticationToken(
                "someone", "credentials",
                List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList());
        token.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private void run(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        filter.doFilter(request, response, capturingChain());
    }

    @Test
    void actuatorIsTheOnlyPathTheFilterDoesNotRunOnAtAll() {
        MockHttpServletRequest actuator = new MockHttpServletRequest("GET", "/actuator/health");
        actuator.setRequestURI("/actuator/health");
        MockHttpServletRequest api = new MockHttpServletRequest("GET", "/api/v1/billing/plans/web");
        api.setRequestURI("/api/v1/billing/plans/web");

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(filter, "shouldNotFilter", actuator))
                .isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(filter, "shouldNotFilter", api))
                .isFalse();
    }

    @Test
    void selfRegistrationDeclaresItselfUnscoped() throws Exception {
        run("/api/v1/auth/register");

        assertThat(observed.unscopedReason()).contains("Self-registration");
        assertThat(observed.orgId()).isNull();
        assertThat(observed.bypassed()).isFalse();
    }

    @Test
    void theCurrentUserLookupDeclaresItselfUnscoped() throws Exception {
        // This one loads Attachment, a tenant-scoped entity, so without the declaration the
        // fail-closed load boundary would refuse the caller's own profile.
        authenticateWith(ORG_MEMBER_7);

        run("/api/v1/user/web");

        assertThat(observed.unscopedReason()).contains("current-user lookup");
        assertThat(observed.orgId()).isNull();
    }

    @Test
    void thePathsBeneathUserWebAreStillOrdinaryTenantRequests() throws Exception {
        authenticateWith(ORG_MEMBER_7);

        run("/api/v1/user/web/employees");

        assertThat(observed.orgId()).isEqualTo(7L);
        assertThat(observed.unscopedReason()).isNull();
    }

    @Test
    void billingRunsInsideTenantIsolationLikeAnyOtherAuthenticatedSurface() throws Exception {
        // This assertion used to be its exact opposite, and the opposite was the defect. The
        // filter declared every /billing path unscoped, which left TenantContext with no
        // organization id, and every endpoint on the billing surface is guarded by
        // @orgSecurity.hasAnyOrgRoleForCurrentTenant or isMemberOfCurrentTenant, both of which
        // refuse a null organization outright. The declaration was therefore refusing the whole
        // self-service billing surface to every caller in every environment. See #640.
        authenticateWith(ORG_MEMBER_7);

        run("/api/v1/billing/subscriptions/web/current");

        assertThat(observed.orgId()).isEqualTo(7L);
        assertThat(observed.unscopedReason()).isNull();
        assertThat(observed.bypassed()).isFalse();
    }

    @Test
    void theBillingGuardsThatRefusedEveryoneNowResolve() throws Exception {
        // The composed statement of #640, and the one worth keeping: it is not the declaration
        // that mattered but what the declaration did to the guard downstream of it. Running the
        // real OrganizationSecurityService inside the chain is what makes this a test of the
        // defect rather than of the filter's internals. Against the old filter both of these
        // are false for every caller, because both open with a null check on the organization id.
        OrganizationSecurityService orgSecurity = new OrganizationSecurityService(null, null);
        authenticateWith(ORG_MEMBER_7, "ORG_7_ROLE_system-admin");

        boolean[] guards = new boolean[2];
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/billing/subscriptions/web/current");
        request.setRequestURI("/api/v1/billing/subscriptions/web/current");
        filter.doFilter(request, response, (req, res) -> {
            guards[0] = orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin");
            guards[1] = orgSecurity.isMemberOfCurrentTenant();
        });

        assertThat(guards[0]).as("hasAnyOrgRoleForCurrentTenant on the billing surface").isTrue();
        assertThat(guards[1]).as("isMemberOfCurrentTenant on the billing surface").isTrue();
    }

    @Test
    void anAuthenticatedUserWithNoMembershipDeclaresItselfUnscoped() throws Exception {
        // The state between signing up and joining an organization. The endpoints that get a
        // user out of it read and write tenant-scoped rows, scoped by the caller's own user id.
        authenticateWith("ROLE_USER");

        run("/api/v1/organization/web");

        assertThat(observed.unscopedReason()).contains("no organization membership");
        assertThat(observed.orgId()).isNull();
        assertThat(observed.bypassed()).isFalse();
    }

    @Test
    void anUnauthenticatedRequestDeclaresItselfUnscoped() throws Exception {
        run("/api/v1/project/web");

        assertThat(observed.unscopedReason()).contains("Unauthenticated");
        assertThat(observed.orgId()).isNull();
    }

    @Test
    void anOrdinaryMemberRequestSetsTheOrganizationAndDeclaresNothingElse() throws Exception {
        authenticateWith(ORG_MEMBER_7);

        run("/api/v1/project/web");

        assertThat(observed.orgId()).isEqualTo(7L);
        assertThat(observed.unscopedReason()).isNull();
        assertThat(observed.bypassed()).isFalse();
    }

    @Test
    void aGlobalAdminStillBypassesRatherThanDeclaringItselfUnscoped() throws Exception {
        authenticateWith("organization:admin");

        run("/api/v1/project/web");

        assertThat(observed.bypassed()).isTrue();
        assertThat(observed.unscopedReason()).isNull();
    }

    @Test
    void aMultiOrganizationCallerMustSayWhichOrganizationTheyAreBillingFor() throws Exception {
        // The one thing removing the billing declaration actually changes for a caller. Billing
        // now goes down the ordinary org-resolution branch, so a user who belongs to more than
        // one organization has to name the one they mean, exactly as they already do everywhere
        // else. Previously this request was declared unscoped and then refused by the guard, so
        // the caller got a 403 that told them nothing about what to send.
        authenticateWith(ORG_MEMBER_7, "ORG_MEMBER_9");

        run("/api/v1/billing/subscriptions/web/current");

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("X-Organization-Id");
        assertThat(observed).as("the chain is not reached").isNull();
    }

    @Test
    void anOrgRoleWithoutMembershipResolvesNoTenant() throws Exception {
        // JwtAuthConverter mints ORG_MEMBER_{id} from the flat group "/org-{id}" and
        // ORG_{id}_ROLE_{role} from the subgroup "/org-{id}/{role}", independently, so a
        // user placed only in the subgroup arrives holding the role and not the membership.
        // The filter resolves an organization from ORG_MEMBER_ authorities alone, so that
        // caller gets no organization at all.
        authenticateWith("ORG_7_ROLE_system-admin");

        run("/api/v1/project/web");

        assertThat(observed.orgId()).isNull();
        assertThat(observed.unscopedReason()).contains("no organization membership");
    }

    @Test
    void anOrgRoleWithoutMembershipIsRefusedWhenItNamesTheOrganization() throws Exception {
        // The other half: naming the organization explicitly does not get the role-holder in
        // either, because the header branch checks the same ORG_MEMBER_ authority.
        authenticateWith("ORG_7_ROLE_system-admin");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/project/web");
        request.setRequestURI("/api/v1/project/web");
        request.addHeader("X-Organization-Id", "7");
        filter.doFilter(request, response, capturingChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(observed).as("the chain is not reached").isNull();
    }

    @Test
    void theRoleGuardCannotSucceedWhereTheMembershipGuardFails() throws Exception {
        // The invariant behind #709. Both guards open on TenantContext.getCurrentOrgId(), and
        // the filter only ever sets it after confirming ORG_MEMBER_{id}, so
        // hasAnyOrgRoleForCurrentTenant implies isMemberOfCurrentTenant for every request that
        // reaches a controller. A guard reading "member OR role" therefore decides on the
        // membership clause alone; the role clause cannot change the answer. Run against the
        // real OrganizationSecurityService inside the chain, as #640's test does, because the
        // claim is about the two of them together rather than either one's internals.
        OrganizationSecurityService orgSecurity = new OrganizationSecurityService(null, null);
        authenticateWith("ORG_7_ROLE_system-admin");

        boolean[] guards = new boolean[2];
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/project/web");
        request.setRequestURI("/api/v1/project/web");
        filter.doFilter(request, response, (req, res) -> {
            guards[0] = orgSecurity.isMemberOfCurrentTenant();
            guards[1] = orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin");
        });

        assertThat(guards[0]).as("isMemberOfCurrentTenant for a role-holder who is not a member").isFalse();
        assertThat(guards[1]).as("hasAnyOrgRoleForCurrentTenant for the same caller").isFalse();
    }

    @Test
    void aGlobalAdminBypassLeavesBothTenantGuardsRefusing() throws Exception {
        // The bypass branch sets no organization id, so both guards keep refusing. Worth
        // pinning next to the invariant above: it is the one caller for whom neither clause of
        // a "member OR role" guard can ever be true, whatever the clauses say.
        OrganizationSecurityService orgSecurity = new OrganizationSecurityService(null, null);
        authenticateWith("organization:admin");

        boolean[] guards = new boolean[2];
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/project/web");
        request.setRequestURI("/api/v1/project/web");
        filter.doFilter(request, response, (req, res) -> {
            guards[0] = orgSecurity.isMemberOfCurrentTenant();
            guards[1] = orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin");
        });

        assertThat(guards[0]).isFalse();
        assertThat(guards[1]).isFalse();
    }

    @Test
    void everyScopeIsClearedOnTheWayOut() throws Exception {
        authenticateWith(ORG_MEMBER_7);
        run("/api/v1/user/web");

        assertThat(TenantContext.isScopeDeclared()).isFalse();
        assertThat(TenantContext.getUnscopedReason()).isNull();
    }
}
