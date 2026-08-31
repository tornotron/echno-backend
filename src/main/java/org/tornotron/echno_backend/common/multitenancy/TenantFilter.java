package org.tornotron.echno_backend.common.multitenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class TenantFilter extends OncePerRequestFilter {

    private static final String ORG_HEADER = "X-Organization-Id";
    private static final String ORG_MEMBER_PREFIX = "ORG_MEMBER_";
    private static final String ORG_ADMIN_AUTHORITY = "organization:admin";

    @Value("${API.VERSION}")
    private String backendVersion;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            // Pre-tenant endpoints, declared rather than merely skipped. These run before a
            // tenant exists or deliberately outside one, and at least /user/web materializes
            // Attachment, which is tenant-scoped. Declaring it here is what keeps that a
            // recorded decision instead of the missing-scope failure the guard now raises.
            // Nothing else about these requests changes: no membership check, no bypass, no
            // organization id, exactly as when they were skipped outright.
            String preTenantReason = preTenantReason(request);
            if (preTenantReason != null) {
                TenantContext.declareUnscoped(preTenantReason);
                log.debug("[TenantFilter] {} runs with no tenant: {}",
                        request.getRequestURI(), preTenantReason);
                filterChain.doFilter(request, response);
                return;
            }

            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

            log.debug("[TenantFilter] path={}, auth={}, authorities={}",
                    request.getRequestURI(),
                    authentication != null ? authentication.getName() : "null",
                    authentication != null ? authentication.getAuthorities() : "N/A");

            if (authentication == null || !authentication.isAuthenticated()) {
                log.debug("[TenantFilter] No authentication, skipping");
                TenantContext.declareUnscoped("Unauthenticated request to " + request.getRequestURI());
                filterChain.doFilter(request, response);
                return;
            }

            // Check if user is a global admin — bypass tenant context. This
            // disables both the org filter and the fail-closed load listener for
            // the whole request, so audit who did it and against what, at WARN,
            // for accountability (the bypass is otherwise invisible).
            if (hasAuthority(authentication, ORG_ADMIN_AUTHORITY)) {
                log.warn("[TenantFilter] Tenant isolation bypassed for global admin '{}' on {} {}",
                        authentication.getName(), request.getMethod(), request.getRequestURI());
                TenantContext.setBypass(true);
                filterChain.doFilter(request, response);
                return;
            }

            String orgHeader = request.getHeader(ORG_HEADER);
            log.debug("[TenantFilter] X-Organization-Id header={}", orgHeader);

            if (orgHeader != null && !orgHeader.isBlank()) {
                Long orgId;
                try {
                    orgId = Long.parseLong(orgHeader.trim());
                } catch (NumberFormatException e) {
                    sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid X-Organization-Id header value");
                    return;
                }

                if (!hasAuthority(authentication, ORG_MEMBER_PREFIX + orgId)) {
                    sendError(response, HttpServletResponse.SC_FORBIDDEN,
                            "You are not a member of organization " + orgId);
                    return;
                }

                TenantContext.setCurrentOrgId(orgId);
                log.debug("[TenantFilter] Tenant context set to organization {}", orgId);

            } else {
                // No header — try to infer from single membership
                List<Long> orgIds = authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .filter(a -> a.startsWith(ORG_MEMBER_PREFIX))
                        .map(a -> a.substring(ORG_MEMBER_PREFIX.length()))
                        .map(Long::parseLong)
                        .toList();

                if (orgIds.size() == 1) {
                    TenantContext.setCurrentOrgId(orgIds.getFirst());
                    log.debug("Tenant context inferred to organization {}", orgIds.getFirst());
                } else if (orgIds.size() > 1) {
                    sendError(response, HttpServletResponse.SC_BAD_REQUEST,
                            "X-Organization-Id header required when user belongs to multiple organizations");
                    return;
                } else {
                    // No ORG_MEMBER_ authority at all. This is the state a user is in between
                    // signing up and joining or creating an organization, and the endpoints that
                    // get them out of it (the profile lookup, organization creation, invite-code
                    // redemption) read and write tenant-scoped rows on the way. Their scoping
                    // comes from the queries themselves, which are keyed on the caller's own user
                    // id or on an invite code, and not from the tenant layer. Saying so here is
                    // what separates it from a request that lost its organization by accident.
                    TenantContext.declareUnscoped(
                            "Authenticated user holds no organization membership");
                    log.debug("[TenantFilter] No organization membership, running with no tenant");
                }
            }

            filterChain.doFilter(request, response);

        } finally {
            TenantContext.clear();
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Actuator is the one path the filter does not run on at all. It is served entirely by
        // Spring Boot's own endpoints, reaches no entity, and a health check should not pay for
        // a tenant decision. Everything else, including the three pre-tenant API paths that used
        // to be listed here, now runs through doFilterInternal so the absence of a tenant is
        // declared rather than merely implied by the filter never having run.
        return request.getRequestURI().startsWith("/actuator");
    }

    /**
     * The API paths that deliberately carry no tenant, and why. These were previously skipped by
     * {@link #shouldNotFilter}, which had the same effect on the request and left nothing behind
     * to say it was intended.
     *
     * <p>The organization and user endpoints in general are intentionally filtered: they rely on
     * per-id membership checks, not a blanket exemption. Only the exact {@code /user/web} lookup
     * is listed, not the paths beneath it. (Two earlier patterns, {@code /organizations} and
     * {@code /users/profile}, never matched the real singular paths and were removed to avoid the
     * false impression that those endpoints are unfiltered.)
     *
     * <p>{@code /billing} used to be listed here too, with the reason "Billing is keyed on the
     * user rather than the organization". That was a description of how {@code Subscription} is
     * stored, not a reason for the surface to sit outside tenant isolation, and it had a cost
     * that was not obvious from reading it: an unscoped declaration leaves
     * {@code TenantContext.getCurrentOrgId()} null, and every billing endpoint is guarded by
     * {@code @orgSecurity.hasAnyOrgRoleForCurrentTenant} or {@code isMemberOfCurrentTenant},
     * both of which refuse a null organization. The declaration was therefore refusing the whole
     * self-service billing surface to everyone, in every environment. Billing now runs inside
     * tenant isolation like any other authenticated surface. Nothing it reaches is a
     * {@code TenantScopedEntity}, so the Hibernate org filter has nothing to act on and no query
     * changes; what changes is that the organization id the guards were written against is
     * present. The genuinely cross-organization admin reads belong on a method-level declaration,
     * not on a path prefix that also catches the self-service ones.
     *
     * @return the reason to record, or null if this request is an ordinary tenant-scoped one
     */
    private String preTenantReason(HttpServletRequest request) {
        String path = request.getRequestURI();
        String apiRoot = "/api/" + backendVersion;

        if (path.equals(apiRoot + "/auth/register")) {
            return "Self-registration runs before the caller belongs to any organization";
        }
        if (path.equals(apiRoot + "/user/web")) {
            return "The current-user lookup answers across every organization the caller belongs to";
        }
        return null;
    }

    private boolean hasAuthority(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(authority));
    }

    private void sendError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
