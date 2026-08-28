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
    void billingDeclaresItselfUnscoped() throws Exception {
        authenticateWith(ORG_MEMBER_7);

        run("/api/v1/billing/subscriptions/web/current");

        assertThat(observed.unscopedReason()).contains("Billing");
        assertThat(observed.orgId()).isNull();
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
    void everyScopeIsClearedOnTheWayOut() throws Exception {
        authenticateWith(ORG_MEMBER_7);
        run("/api/v1/user/web");

        assertThat(TenantContext.isScopeDeclared()).isFalse();
        assertThat(TenantContext.getUnscopedReason()).isNull();
    }
}
