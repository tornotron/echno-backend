package org.tornotron.echno_backend.common.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserContextService;

import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the @orgSecurity bean that backs every @PreAuthorize on the API.
 * The endpoint-existence ArchUnit rule proves a guard is present; these tests prove
 * the guard is correct: org-scoped membership and role authorities are matched by
 * exact org id, the current-tenant variants read TenantContext, and the self/admin
 * composition falls back correctly. No Spring context or database is needed.
 */
class OrganizationSecurityServiceTest {

    private final UserContextService userContextService = mock(UserContextService.class);
    private final EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
    private final OrganizationSecurityService svc =
            new OrganizationSecurityService(userContextService, employeeRepository);

    @AfterEach
    void clearState() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private void authWith(String... authorities) {
        var granted = Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user", "n/a", granted));
    }

    private Employee employeeOwnedByUser(Long userId) {
        User u = new User();
        u.setId(userId);
        Employee e = new Employee();
        e.setUser(u);
        return e;
    }

    @Test
    void isMember_matchesTheOrgMemberAuthorityByExactId() {
        authWith("ORG_MEMBER_5");
        assertThat(svc.isMember(5L)).isTrue();
        assertThat(svc.isMember(6L)).isFalse();
    }

    @Test
    void isMember_falseWhenUnauthenticated() {
        assertThat(svc.isMember(5L)).isFalse();
    }

    @Test
    void isMemberOrAdmin_trueForMemberOrGlobalAdminOfAnyOrg() {
        authWith("ORG_MEMBER_5");
        assertThat(svc.isMemberOrAdmin(5L)).isTrue();

        SecurityContextHolder.clearContext();
        authWith("organization:admin");
        assertThat(svc.isMemberOrAdmin(999L)).isTrue();
    }

    @Test
    void isMemberOrAdmin_falseWhenNeitherMemberNorAdmin() {
        authWith("ORG_MEMBER_7");
        assertThat(svc.isMemberOrAdmin(5L)).isFalse();
    }

    @Test
    void hasOrgRole_matchesExactOrgAndRole() {
        authWith("ORG_5_ROLE_system-admin");
        assertThat(svc.hasOrgRole(5L, "system-admin")).isTrue();
        assertThat(svc.hasOrgRole(5L, "hr-admin")).isFalse();
        assertThat(svc.hasOrgRole(6L, "system-admin")).isFalse();
    }

    @Test
    void hasAnyOrgRole_trueWhenAtLeastOneRoleMatches() {
        authWith("ORG_5_ROLE_project-manager");
        assertThat(svc.hasAnyOrgRole(5L, "system-admin", "project-manager")).isTrue();
        assertThat(svc.hasAnyOrgRole(5L, "system-admin", "hr-admin")).isFalse();
    }

    @Test
    void isMemberOfCurrentTenant_readsTheOrgIdFromTenantContext() {
        authWith("ORG_MEMBER_5");
        TenantContext.setCurrentOrgId(5L);
        assertThat(svc.isMemberOfCurrentTenant()).isTrue();
    }

    @Test
    void isMemberOfCurrentTenant_falseWhenNoTenantContextSet() {
        authWith("ORG_MEMBER_5");
        assertThat(svc.isMemberOfCurrentTenant()).isFalse();
    }

    @Test
    void hasAnyOrgRoleForCurrentTenant_readsTheOrgIdFromTenantContext() {
        authWith("ORG_5_ROLE_hr-admin");
        TenantContext.setCurrentOrgId(5L);
        assertThat(svc.hasAnyOrgRoleForCurrentTenant("system-admin", "hr-admin")).isTrue();

        TenantContext.clear();
        assertThat(svc.hasAnyOrgRoleForCurrentTenant("hr-admin")).isFalse();
    }

    @Test
    void isSelfUser_comparesAgainstTheCurrentUserId() {
        when(userContextService.getCurrentUserId()).thenReturn(10L);
        assertThat(svc.isSelfUser(10L)).isTrue();
        assertThat(svc.isSelfUser(11L)).isFalse();
    }

    @Test
    void isSelfUser_falseWhenNoCurrentUser() {
        when(userContextService.getCurrentUserId()).thenReturn(null);
        assertThat(svc.isSelfUser(10L)).isFalse();
    }

    @Test
    void isSelfInCurrentTenant_trueWhenTheEmployeeBelongsToTheCurrentUser() {
        TenantContext.setCurrentOrgId(5L);
        when(userContextService.getCurrentUserId()).thenReturn(10L);
        when(employeeRepository.findByIdAndOrganizationId(7L, 5L))
                .thenReturn(Optional.of(employeeOwnedByUser(10L)));
        assertThat(svc.isSelfInCurrentTenant(7L)).isTrue();
    }

    @Test
    void isSelfInCurrentTenant_falseForAnEmployeeOwnedByAnotherUser() {
        TenantContext.setCurrentOrgId(5L);
        when(userContextService.getCurrentUserId()).thenReturn(10L);
        when(employeeRepository.findByIdAndOrganizationId(7L, 5L))
                .thenReturn(Optional.of(employeeOwnedByUser(99L)));
        assertThat(svc.isSelfInCurrentTenant(7L)).isFalse();
    }

    @Test
    void isSelfInCurrentTenant_falseWhenNoTenantContext() {
        when(userContextService.getCurrentUserId()).thenReturn(10L);
        assertThat(svc.isSelfInCurrentTenant(7L)).isFalse();
    }

    @Test
    void isSelfOrHasAnyOrgRole_trueWhenSelfRegardlessOfRole() {
        TenantContext.setCurrentOrgId(5L);
        when(userContextService.getCurrentUserId()).thenReturn(10L);
        when(employeeRepository.findByIdAndOrganizationId(7L, 5L))
                .thenReturn(Optional.of(employeeOwnedByUser(10L)));
        assertThat(svc.isSelfOrHasAnyOrgRole(7L, "system-admin")).isTrue();
    }

    @Test
    void isSelfOrHasAnyOrgRole_trueWhenNotSelfButHoldsTheRole() {
        TenantContext.setCurrentOrgId(5L);
        when(userContextService.getCurrentUserId()).thenReturn(10L);
        when(employeeRepository.findByIdAndOrganizationId(7L, 5L)).thenReturn(Optional.empty());
        authWith("ORG_5_ROLE_hr-admin");
        assertThat(svc.isSelfOrHasAnyOrgRole(7L, "hr-admin")).isTrue();
    }

    @Test
    void isSelfOrHasAnyOrgRole_falseWhenNeitherSelfNorRole() {
        TenantContext.setCurrentOrgId(5L);
        when(userContextService.getCurrentUserId()).thenReturn(10L);
        when(employeeRepository.findByIdAndOrganizationId(7L, 5L)).thenReturn(Optional.empty());
        authWith("ORG_MEMBER_5");
        assertThat(svc.isSelfOrHasAnyOrgRole(7L, "hr-admin")).isFalse();
    }
}
