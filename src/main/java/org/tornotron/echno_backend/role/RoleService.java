package org.tornotron.echno_backend.role;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.OrgRoleAuthority;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.role.dto.CustomRoleRequest;
import org.tornotron.echno_backend.role.dto.EmployeePermissionsDto;
import org.tornotron.echno_backend.role.dto.PermissionDto;
import org.tornotron.echno_backend.role.dto.RoleDto;
import org.tornotron.echno_backend.role.dto.RoleMemberDto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Roles and what they grant, for the organization the request is scoped to.
 *
 * <p>Built-in roles always exist; an organization can change which permissions each grants, except
 * system-admin, which always grants everything. Custom roles are made, renamed, re-permissioned,
 * assigned and deleted by the organization. Every change is in force on the affected people's next
 * request, because the permissions are read from the database on every request
 * ({@code PermissionAuthorityProvider}).
 */
@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleDefinitionRepository roleRepository;
    private final OrganizationRepository organizationRepository;
    private final EmployeeRepository employeeRepository;

    /** Every permission there is, grouped by module and then by code. */
    public List<PermissionDto> catalogue() {
        return Arrays.stream(Permission.values())
                .sorted(Comparator.comparing(Permission::getModule).thenComparing(Permission::getCode))
                .map(PermissionDto::from)
                .toList();
    }

    /** The built-in roles, then the organization's custom roles by name, each with what it grants. */
    @Transactional(readOnly = true)
    public List<RoleDto> listRoles() {
        Long organizationId = TenantContext.getCurrentOrgId();
        Map<OrgRole, RoleDefinition> overrides = roleRepository
                .findByOrganization_IdAndSystemRoleIsNotNull(organizationId).stream()
                .collect(Collectors.toMap(RoleDefinition::getSystemRole, Function.identity()));

        List<RoleDto> roles = new ArrayList<>();
        for (OrgRole role : OrgRole.values()) {
            RoleDefinition override = overrides.get(role);
            roles.add(RoleDto.builtIn(role,
                    DefaultRolePermissions.effective(role, override == null ? null : override.getPermissions())));
        }
        roleRepository.findByOrganization_IdAndSystemRoleIsNullOrderByNameAsc(organizationId)
                .forEach(role -> roles.add(RoleDto.custom(role)));
        return roles;
    }

    /** The roles one employee holds here, built-in first, each with what it grants. */
    @Transactional(readOnly = true)
    public List<RoleDto> rolesOfEmployee(Long employeeId) {
        Long organizationId = TenantContext.getCurrentOrgId();
        Employee employee = requireEmployee(employeeId);
        List<RoleDto> roles = new ArrayList<>();
        for (OrgRole role : employee.getOrgRoles().stream().sorted().toList()) {
            RoleDefinition override = roleRepository.findByOrganization_IdAndSystemRole(organizationId, role)
                    .orElse(null);
            roles.add(RoleDto.builtIn(role,
                    DefaultRolePermissions.effective(role, override == null ? null : override.getPermissions())));
        }
        roleRepository.findCustomRolesOfEmployee(organizationId, employeeId)
                .forEach(role -> roles.add(RoleDto.custom(role)));
        return roles;
    }

    /**
     * The permission codes the caller holds in the current organization, read off this request's
     * authorities. What a client uses to decide which buttons to show.
     */
    public List<String> myPermissions() {
        Long organizationId = TenantContext.getCurrentOrgId();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (organizationId == null || authentication == null) {
            return List.of();
        }
        String prefix = OrgRoleAuthority.permission(organizationId, "");
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(prefix))
                .map(authority -> authority.substring(prefix.length()))
                .sorted()
                .toList();
    }

    /**
     * Makes a built-in role grant exactly these permissions in this organization.
     *
     * <p>Stored as the difference from the role's defaults, so a permission added to the code later
     * still reaches this organization's role unless it was taken away here. Sending the defaults
     * removes the stored difference altogether.
     */
    @Transactional
    public RoleDto setBuiltInPermissions(OrgRole role, Set<String> codes) {
        if (!DefaultRolePermissions.isEditable(role)) {
            throw new IllegalArgumentException("'" + role.getRoleName() + "' always holds every permission "
                    + "and cannot be changed");
        }
        Long organizationId = TenantContext.getCurrentOrgId();
        Set<Permission> wanted = parse(codes);
        Map<String, Boolean> overrides = DefaultRolePermissions.overridesFor(role, wanted);

        RoleDefinition stored = roleRepository.findByOrganization_IdAndSystemRole(organizationId, role).orElse(null);
        if (overrides.isEmpty()) {
            if (stored != null) {
                roleRepository.delete(stored);
            }
        } else {
            RoleDefinition definition = stored != null ? stored : newDefinition(organizationId, role.getRoleName());
            definition.setSystemRole(role);
            definition.getPermissions().clear();
            definition.getPermissions().putAll(overrides);
            roleRepository.save(definition);
        }
        return RoleDto.builtIn(role, wanted);
    }

    @Transactional
    public RoleDto createCustomRole(CustomRoleRequest request) {
        Long organizationId = TenantContext.getCurrentOrgId();
        String name = request.name().trim();
        requireAvailableName(organizationId, name, null);

        RoleDefinition role = newDefinition(organizationId, name);
        role.setDescription(blankToNull(request.description()));
        grantExactly(role, parse(request.permissions()));
        return RoleDto.custom(roleRepository.saveAndFlush(role));
    }

    /** Replaces a custom role's name, description and permissions. Its members keep it. */
    @Transactional
    public RoleDto updateCustomRole(UUID roleId, CustomRoleRequest request) {
        Long organizationId = TenantContext.getCurrentOrgId();
        RoleDefinition role = requireCustomRole(roleId);
        String name = request.name().trim();
        requireAvailableName(organizationId, name, roleId);

        role.setName(name);
        role.setDescription(blankToNull(request.description()));
        grantExactly(role, parse(request.permissions()));
        return RoleDto.custom(roleRepository.saveAndFlush(role));
    }

    /** Deletes a custom role. Everyone holding it loses it, and what it granted, on their next request. */
    @Transactional
    public void deleteCustomRole(UUID roleId) {
        roleRepository.delete(requireCustomRole(roleId));
    }

    @Transactional(readOnly = true)
    public List<RoleMemberDto> members(UUID roleId) {
        return requireCustomRole(roleId).getMembers().stream()
                .sorted(Comparator.comparing(Employee::getEmployeeName, Comparator.nullsLast(String::compareTo)))
                .map(RoleMemberDto::from)
                .toList();
    }

    /**
     * One employee's direct grants, and everything they hold here once their roles are added in.
     * The effective list is worked out the same way the request's authorities are.
     */
    @Transactional(readOnly = true)
    public EmployeePermissionsDto permissionsOfEmployee(Long employeeId) {
        Employee employee = requireEmployee(employeeId);
        List<String> direct = known(employee.getDirectPermissions());
        Set<String> effective = new TreeSet<>(direct);
        rolesOfEmployee(employeeId).forEach(role -> effective.addAll(role.permissions()));
        return new EmployeePermissionsDto(employee.getId(), direct, List.copyOf(effective));
    }

    /** Grants one permission to an employee directly. Granting it twice changes nothing. */
    @Transactional
    public EmployeePermissionsDto grantDirect(Long employeeId, String code) {
        Permission permission = parse(Set.of(code)).iterator().next();
        Employee employee = requireEmployee(employeeId);
        employee.getDirectPermissions().add(permission.getCode());
        employeeRepository.save(employee);
        return permissionsOfEmployee(employeeId);
    }

    /**
     * Takes away a permission granted to an employee directly. A permission one of their roles
     * grants stays; revoking one they were not granted changes nothing.
     */
    @Transactional
    public EmployeePermissionsDto revokeDirect(Long employeeId, String code) {
        Employee employee = requireEmployee(employeeId);
        employee.getDirectPermissions().remove(code.trim());
        employeeRepository.save(employee);
        return permissionsOfEmployee(employeeId);
    }

    /** Gives an employee of this organization a custom role. Giving it twice changes nothing. */
    @Transactional
    public void assign(UUID roleId, Long employeeId) {
        RoleDefinition role = requireCustomRole(roleId);
        role.getMembers().add(requireEmployee(employeeId));
        roleRepository.save(role);
    }

    /** Takes a custom role away from an employee. Taking away one they do not hold changes nothing. */
    @Transactional
    public void unassign(UUID roleId, Long employeeId) {
        RoleDefinition role = requireCustomRole(roleId);
        Employee employee = requireEmployee(employeeId);
        role.getMembers().removeIf(member -> member.getId().equals(employee.getId()));
        roleRepository.save(role);
    }

    private RoleDefinition newDefinition(Long organizationId, String name) {
        RoleDefinition definition = new RoleDefinition();
        definition.setOrganization(organizationRepository.getReferenceById(organizationId));
        definition.setName(name);
        return definition;
    }

    private static void grantExactly(RoleDefinition role, Set<Permission> permissions) {
        role.getPermissions().clear();
        permissions.forEach(permission -> role.getPermissions().put(permission.getCode(), true));
    }

    private RoleDefinition requireCustomRole(UUID roleId) {
        return roleRepository.findByIdAndOrganization_IdAndSystemRoleIsNull(roleId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Custom role " + roleId + " was not found in this organization"));
    }

    private Employee requireEmployee(Long employeeId) {
        return employeeRepository.findByIdAndOrganizationId(employeeId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee with ID " + employeeId + " was not found in this organization"));
    }

    /**
     * A custom role's name has to be free in the organization, and may not be a built-in role's
     * name: a custom "system-admin" holding no permissions would read as the real one everywhere a
     * role is listed.
     */
    private void requireAvailableName(Long organizationId, String name, UUID exceptRoleId) {
        boolean builtInName = Arrays.stream(OrgRole.values())
                .anyMatch(role -> role.getRoleName().equalsIgnoreCase(name) || role.name().equalsIgnoreCase(name));
        if (builtInName) {
            throw new IllegalArgumentException("'" + name + "' is the name of a built-in role; choose another");
        }
        boolean taken = exceptRoleId == null
                ? roleRepository.existsByOrganization_IdAndNameIgnoreCase(organizationId, name)
                : roleRepository.existsByOrganization_IdAndNameIgnoreCaseAndIdNot(organizationId, name, exceptRoleId);
        if (taken) {
            throw new DuplicateResourceException("A role named '" + name + "' already exists in this organization");
        }
    }

    /** The permissions these codes name, refusing the request if any code is unknown. */
    private static Set<Permission> parse(Set<String> codes) {
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        List<String> unknown = new ArrayList<>();
        for (String code : codes) {
            String trimmed = code == null ? "" : code.trim();
            Permission.fromCode(trimmed).ifPresentOrElse(permissions::add, () -> unknown.add(trimmed));
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown permission(s): " + String.join(", ", unknown)
                    + ". GET /api/v1/roles/web/permissions lists the valid codes.");
        }
        return permissions;
    }

    /** The codes that still name a permission, sorted. A code removed from the catalogue drops out. */
    private static List<String> known(Set<String> codes) {
        return codes.stream().filter(code -> Permission.fromCode(code).isPresent()).sorted().toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
