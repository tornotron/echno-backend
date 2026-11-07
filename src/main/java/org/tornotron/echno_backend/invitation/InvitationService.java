package org.tornotron.echno_backend.invitation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.identity.task.IdentityTaskQueue;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.employee.dto.EmployeeJoinOrgDto;
import org.tornotron.echno_backend.invitation.dto.InvitationCreationDto;
import org.tornotron.echno_backend.invitation.dto.InvitationDto;
import org.tornotron.echno_backend.invitation.dto.InvitationRoleDto;
import org.tornotron.echno_backend.modules.access.api.AccessChanges;
import org.tornotron.echno_backend.modules.access.api.AccessRoleAssignments;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserContextService;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Invitations to join an organization, with the roles the person gets and the employee details the
 * join form collects. Nothing here creates an employee: that happens when the person accepts.
 *
 * <p>Every provider call goes through {@link IdentityTaskQueue}, after the change commits. A resend
 * repeats the same link and keeps its expiry, because the provider cannot extend one. Resending an
 * expired invitation renews it instead: a new expiry, and the provider's old link is revoked and a new
 * one sent, whose id the record then takes.
 *
 * <p>Plain SQL with the organization in every statement, because the delivery side runs with no
 * tenant and the acceptance side looks invitations up by address across organizations.
 */
@Service
public class InvitationService {

    static final String TARGET = "invitation";
    static final String CREATED = "invitation.created";
    static final String RESENT = "invitation.resent";
    static final String RENEWED = "invitation.renewed";
    static final String REVOKED = "invitation.revoked";

    private static final String COLUMNS = """
            id, email, status, expires_at, sent_at, accepted_at, revoked_at, created_at, invited_by_user_id,
            CAST(employee_details AS TEXT) AS employee_details
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AccessRoleAssignments assignments;
    private final AccessChanges changes;
    private final IdentityTaskQueue tasks;
    private final UserContextService users;
    private final Clock clock;
    private final Duration expiry;

    public InvitationService(JdbcTemplate jdbc, ObjectMapper json, AccessRoleAssignments assignments,
                             AccessChanges changes, IdentityTaskQueue tasks, UserContextService users,
                             @Value("${echno.invitations.expiry-days:7}") int expiryDays) {
        if (expiryDays < 1 || expiryDays > 30) {
            throw new IllegalStateException("echno.invitations.expiry-days must be 1 to 30, not " + expiryDays);
        }
        this.jdbc = jdbc;
        this.json = json;
        this.assignments = assignments;
        this.changes = changes;
        this.tasks = tasks;
        this.users = users;
        this.clock = Clock.systemUTC();
        this.expiry = Duration.ofDays(expiryDays);
    }

    /**
     * Invites a person to the caller's organization. Each role is checked as an assignment by the caller
     * would be, nobody invites their own address, and an address that is already an employee here or
     * already has an open invitation is refused.
     */
    @Transactional
    public InvitationDto invite(InvitationCreationDto request) {
        Long organization = currentOrganization();
        String email = normalized(request.email());
        User caller = users.getCurrentUser();
        if (caller != null && caller.getEmail() != null && normalized(caller.getEmail()).equals(email)) {
            throw new AccessDeniedException("Nobody invites themselves");
        }
        if (isEmployee(organization, email)) {
            throw new DuplicateResourceException("This address already belongs to an employee of the organization");
        }
        for (InvitationRoleDto role : request.roles()) {
            assignments.checkAssignable(role.roleId(), role.projectId());
        }
        requireActiveManager(organization, request.employee().getManagerId());
        return create(organization, email, request.roles(), request.employee(),
                caller == null ? null : caller.getId(), expiry);
    }

    /**
     * Records an invitation and asks for it to be sent, with no caller rules: for the system, which has
     * already decided the person may hold these roles. The address is still unique among open
     * invitations.
     */
    @Transactional
    public InvitationDto create(Long organization, String email, List<InvitationRoleDto> roles,
                                EmployeeJoinOrgDto employee, @Nullable Long invitedByUserId, Duration lasting) {
        String address = normalized(email);
        expireIfLapsed(organization, address);
        Integer open = jdbc.queryForObject(
                "SELECT count(*) FROM invitation WHERE organization_id = ? AND email = ? AND status = 'PENDING'",
                Integer.class, organization, address);
        if (open != null && open > 0) {
            throw new DuplicateResourceException("This address already has an open invitation to the organization");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO invitation (id, organization_id, email, status, expires_at, employee_details,
                                            invited_by_user_id, created_at, updated_at)
                    VALUES (?, ?, ?, 'PENDING', ?, CAST(? AS JSONB), ?, now(), now())
                    """, id, organization, address, Timestamp.from(clock.instant().plus(lasting)),
                    write(employee), invitedByUserId);
        } catch (DuplicateKeyException alreadyOpen) {
            throw new DuplicateResourceException("This address already has an open invitation to the organization");
        }
        for (InvitationRoleDto role : new LinkedHashSet<>(roles)) {
            jdbc.update("""
                    INSERT INTO invitation_role (id, organization_id, invitation_id, role_id, project_id)
                    VALUES (gen_random_uuid(), ?, ?, ?, ?)
                    """, organization, id, role.roleId(), role.projectId());
        }
        changes.record(organization, invitedByUserId, CREATED, TARGET, id.toString(), null,
                state(address, roles, InvitationStage.PENDING));
        tasks.sendInvitation(id);
        return read(organization, id);
    }

    /** The organization's invitations, newest first. */
    @Transactional(readOnly = true)
    public List<InvitationDto> list(PageQuery page) {
        Long organization = currentOrganization();
        List<Row> rows = jdbc.query("SELECT " + COLUMNS + " FROM invitation WHERE organization_id = ? "
                        + "ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
                this::row, organization, page.getPageSize(), (long) page.getPageNo() * page.getPageSize());
        return withRoles(organization, rows);
    }

    @Transactional(readOnly = true)
    public InvitationDto get(UUID id) {
        return read(currentOrganization(), id);
    }

    /**
     * Sends the link again with the same expiry, or, once it has expired, renews it with a new expiry
     * and a new link.
     */
    @Transactional
    public InvitationDto resend(UUID id) {
        Long organization = currentOrganization();
        Row row = lockedRow(organization, id);
        Long actor = users.getCurrentUserId();
        InvitationStage stage = effectiveStage(row);
        if (stage == InvitationStage.PENDING) {
            tasks.resendInvitation(id);
            changes.record(organization, actor, RESENT, TARGET, id.toString(), null, Map.of("email", row.email()));
        } else if (stage == InvitationStage.EXPIRED) {
            try {
                jdbc.update("""
                        UPDATE invitation SET status = 'PENDING', expires_at = ?, updated_at = now()
                        WHERE id = ? AND organization_id = ?
                        """, Timestamp.from(clock.instant().plus(expiry)), id, organization);
            } catch (DuplicateKeyException alreadyOpen) {
                throw new DuplicateResourceException("This address already has an open invitation to the organization");
            }
            tasks.renewInvitation(id);
            changes.record(organization, actor, RENEWED, TARGET, id.toString(),
                    Map.of("expiresAt", row.expiresAt().toString()), Map.of("email", row.email()));
        } else {
            throw new InvalidRequestException("This invitation is " + stage + " and cannot be resent");
        }
        return read(organization, id);
    }

    /** Stops the link working. The provider follows after the change commits. */
    @Transactional
    public InvitationDto revoke(UUID id) {
        Long organization = currentOrganization();
        Row row = lockedRow(organization, id);
        InvitationStage stage = effectiveStage(row);
        if (stage != InvitationStage.PENDING && stage != InvitationStage.EXPIRED) {
            throw new InvalidRequestException("This invitation is " + stage + " and cannot be revoked");
        }
        jdbc.update("""
                UPDATE invitation SET status = 'REVOKED', revoked_at = now(), updated_at = now()
                WHERE id = ? AND organization_id = ?
                """, id, organization);
        tasks.revokeInvitation(id);
        changes.record(organization, users.getCurrentUserId(), REVOKED, TARGET, id.toString(),
                Map.of("status", stage.name()), Map.of("status", InvitationStage.REVOKED.name()));
        return read(organization, id);
    }

    // --- Reading ---------------------------------------------------------------------------------

    record Row(UUID id, String email, InvitationStage stage, Instant expiresAt, @Nullable Instant sentAt,
               @Nullable Instant acceptedAt, @Nullable Instant revokedAt, Instant createdAt,
               @Nullable Long invitedByUserId, String employeeDetails) {
    }

    private InvitationDto read(Long organization, UUID id) {
        return withRoles(organization, List.of(row(organization, id, false))).get(0);
    }

    private Row lockedRow(Long organization, UUID id) {
        return row(organization, id, true);
    }

    private Row row(Long organization, UUID id, boolean lock) {
        return jdbc.query("SELECT " + COLUMNS + " FROM invitation WHERE id = ? AND organization_id = ?"
                        + (lock ? " FOR UPDATE" : ""), this::row, id, organization)
                .stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Invitation " + id + " was not found in this organization"));
    }

    private Row row(ResultSet rs, int index) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("email"),
                InvitationStage.valueOf(rs.getString("status")), instant(rs, "expires_at"), instant(rs, "sent_at"),
                instant(rs, "accepted_at"), instant(rs, "revoked_at"), instant(rs, "created_at"),
                rs.getObject("invited_by_user_id", Long.class), rs.getString("employee_details"));
    }

    private List<InvitationDto> withRoles(Long organization, List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<InvitationRoleDto>> roles = new LinkedHashMap<>();
        rows.forEach(row -> roles.put(row.id(), new ArrayList<>()));
        List<Object> arguments = new ArrayList<>();
        arguments.add(organization);
        arguments.addAll(roles.keySet());
        jdbc.query("SELECT invitation_id, role_id, project_id FROM invitation_role WHERE organization_id = ? "
                        + "AND invitation_id IN (" + String.join(", ", Collections.nCopies(roles.size(), "?"))
                        + ") ORDER BY role_id, project_id",
                rs -> {
                    roles.get(rs.getObject(1, UUID.class)).add(
                            new InvitationRoleDto(rs.getObject(2, UUID.class), rs.getObject(3, Long.class)));
                }, arguments.toArray());
        return rows.stream().map(row -> new InvitationDto(row.id(), row.email(), effectiveStage(row), row.expiresAt(),
                row.sentAt(), row.acceptedAt(), row.revokedAt(), row.createdAt(), row.invitedByUserId(),
                List.copyOf(roles.get(row.id())), read(row.employeeDetails()))).toList();
    }

    /** A pending invitation past its expiry is expired, whether or not the sync has written that yet. */
    private InvitationStage effectiveStage(Row row) {
        return row.stage() == InvitationStage.PENDING && !row.expiresAt().isAfter(clock.instant())
                ? InvitationStage.EXPIRED
                : row.stage();
    }

    // --- Helpers ---------------------------------------------------------------------------------

    private Long currentOrganization() {
        Long organization = TenantContext.getCurrentOrgId();
        if (organization == null) {
            throw new InvalidRequestException("An invitation needs an organization");
        }
        return organization;
    }

    /** An open invitation past its expiry stops counting as open, so the address can be invited again. */
    private void expireIfLapsed(Long organization, String email) {
        jdbc.update("""
                UPDATE invitation SET status = 'EXPIRED', updated_at = now()
                WHERE organization_id = ? AND email = ? AND status = 'PENDING' AND expires_at <= now()
                """, organization, email);
    }

    private boolean isEmployee(Long organization, String email) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM employee e JOIN users_table u ON u.id = e.user_id
                WHERE e.organization_id = ? AND lower(u.email) = ?
                """, Integer.class, organization, email);
        return count != null && count > 0;
    }

    /**
     * The same rule the employee is created under at acceptance: a reporting manager, active here, unless
     * the organization has no active employee yet. Checked now so a link does not fail later for it.
     */
    private void requireActiveManager(Long organization, @Nullable Long managerId) {
        if (managerId == null) {
            Integer active = jdbc.queryForObject(
                    "SELECT count(*) FROM employee WHERE organization_id = ? AND status = 'active'",
                    Integer.class, organization);
            if (active != null && active > 0) {
                throw new InvalidRequestException("employee.managerId is required: a new employee must have a reporting manager");
            }
            return;
        }
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM employee WHERE id = ? AND organization_id = ? AND status = 'active'
                """, Integer.class, managerId, organization);
        if (count == null || count == 0) {
            throw new InvalidRequestException("Manager with ID " + managerId + " is not an active employee of this organization");
        }
    }

    static String normalized(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> state(String email, List<InvitationRoleDto> roles, InvitationStage stage) {
        return Map.of("email", email, "status", stage.name(), "roles", roles.stream()
                .map(role -> role.projectId() == null
                        ? Map.of("role", role.roleId().toString())
                        : Map.of("role", role.roleId().toString(), "project", role.projectId()))
                .toList());
    }

    @Nullable
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private String write(EmployeeJoinOrgDto employee) {
        try {
            return json.writeValueAsString(employee);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Employee details could not be written", e);
        }
    }

    EmployeeJoinOrgDto read(String employeeDetails) {
        try {
            return json.readValue(employeeDetails, EmployeeJoinOrgDto.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Employee details could not be read", e);
        }
    }
}
