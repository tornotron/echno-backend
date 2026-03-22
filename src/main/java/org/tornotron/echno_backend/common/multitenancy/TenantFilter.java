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
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

            log.info("[TenantFilter] path={}, auth={}, authorities={}",
                    request.getRequestURI(),
                    authentication != null ? authentication.getName() : "null",
                    authentication != null ? authentication.getAuthorities() : "N/A");

            if (authentication == null || !authentication.isAuthenticated()) {
                log.info("[TenantFilter] No authentication, skipping");
                filterChain.doFilter(request, response);
                return;
            }

            // Check if user is a global admin — bypass tenant context
            if (hasAuthority(authentication, ORG_ADMIN_AUTHORITY)) {
                log.info("[TenantFilter] Global admin detected, bypassing tenant filter");
                TenantContext.setBypass(true);
                filterChain.doFilter(request, response);
                return;
            }

            String orgHeader = request.getHeader(ORG_HEADER);
            log.info("[TenantFilter] X-Organization-Id header={}", orgHeader);

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
                log.info("[TenantFilter] Tenant context set to organization {}", orgId);

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
                }
                // orgIds.isEmpty() — user has no org memberships, no tenant context set
            }

            filterChain.doFilter(request, response);

        } finally {
            TenantContext.clear();
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator")
                || path.equals("/api/" + backendVersion + "/auth/register")
                || path.startsWith("/api/" + backendVersion + "/organizations")
                || path.startsWith("/api/" + backendVersion + "/users/profile")
                || path.equals("/api/" + backendVersion + "/user/web")
                || path.startsWith("/api/" + backendVersion + "/billing");
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
