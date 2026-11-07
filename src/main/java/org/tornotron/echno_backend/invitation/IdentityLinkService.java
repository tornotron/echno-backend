package org.tornotron.echno_backend.invitation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.identity.ExternalInvitationId;
import org.tornotron.echno_backend.common.identity.ExternalUserId;
import org.tornotron.echno_backend.common.identity.IdentityProvider;
import org.tornotron.echno_backend.common.identity.IdentityTokenMapper;
import org.tornotron.echno_backend.common.identity.InvitationState;
import org.tornotron.echno_backend.common.identity.InvitationStatus;
import org.tornotron.echno_backend.common.identity.Principal;
import org.tornotron.echno_backend.common.identity.UserProfile;
import org.tornotron.echno_backend.common.identity.link.EchnoJwtAuthentication;
import org.tornotron.echno_backend.common.identity.link.IdentityLinks;
import org.tornotron.echno_backend.common.identity.link.UserIdentityLookup;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.employee.EmployeeService;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.employee.dto.EmployeeJoinOrgDto;
import org.tornotron.echno_backend.modules.access.api.AccessChanges;
import org.tornotron.echno_backend.modules.access.api.AccessRoleAssignments;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns an accepted invitation into an employee, the first time the person signs in.
 *
 * <p>Links only when all of these hold: Echno's invitation is still pending, the provider says it was
 * accepted, by this very account, and the account's address, read from the provider's user record and
 * never from the token, is verified and is exactly the invited one. Nothing is ever linked on an
 * address alone. A provider may let a colleague on the same domain accept; that links nothing here, and
 * {@link InvitationSyncJob} shows it to the administrator.
 *
 * <p>Each link is its own transaction: the user and identity rows if new, the employee with the
 * invitation's details, its roles, the invitation marked accepted, and the history. The invitation row
 * is locked first, so two first requests at once make one employee. {@code /user/web} declares no
 * tenant, so the lookup is by address across organizations, and each link works in its invitation's
 * organization.
 */
@Slf4j
@Service
public class IdentityLinkService {

    static final String ACCEPTED = "invitation.accepted";

    private record Candidate(UUID id, Long organization, String providerId) {
    }

    private final JdbcTemplate jdbc;
    private final IdentityProvider provider;
    private final IdentityTokenMapper mapper;
    private final UserIdentityLookup identities;
    private final IdentityLinks links;
    private final UserRepository users;
    private final EmployeeService employees;
    private final AccessRoleAssignments assignments;
    private final AccessChanges changes;
    private final InvitationService invitations;
    private final TransactionTemplate transaction;

    public IdentityLinkService(JdbcTemplate jdbc, IdentityProvider provider, IdentityTokenMapper mapper,
                               UserIdentityLookup identities, IdentityLinks links, UserRepository users,
                               EmployeeService employees, AccessRoleAssignments assignments, AccessChanges changes,
                               InvitationService invitations, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.provider = provider;
        this.mapper = mapper;
        this.identities = identities;
        this.links = links;
        this.users = users;
        this.employees = employees;
        this.assignments = assignments;
        this.changes = changes;
        this.invitations = invitations;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** For the signed-in caller: their Echno user, after linking any invitation they accepted. */
    public Optional<Long> reconcileCurrent() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Long known = EchnoJwtAuthentication.currentUserId();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            return Optional.ofNullable(known);
        }
        return reconcile(mapper.toPrincipal(token.getToken())).or(() -> Optional.ofNullable(known));
    }

    /**
     * Links every invitation this person accepted and Echno has not yet linked.
     *
     * @return their Echno user, if they have one now.
     */
    public Optional<Long> reconcile(Principal principal) {
        ExternalUserId account = new ExternalUserId(principal.subject());
        Optional<Long> existing = identities.userId(principal.issuer(), principal.subject());
        Optional<UserProfile> profile = provider.profile(account);
        if (profile.isEmpty() || !profile.get().emailVerified() || profile.get().email() == null) {
            return existing;
        }
        String email = InvitationService.normalized(profile.get().email());
        Optional<Long> userId = existing;
        for (Candidate candidate : pendingFor(email)) {
            Optional<InvitationStatus> status = provider.invitationState(new ExternalInvitationId(candidate.providerId()));
            if (status.isEmpty() || status.get().state() != InvitationState.ACCEPTED
                    || !account.equals(status.get().acceptedUser())) {
                continue;
            }
            try {
                Optional<Long> linked = transaction.execute(ignored ->
                        link(candidate, principal, account, email, profile.get()));
                if (linked != null && linked.isPresent()) {
                    userId = linked;
                }
            } catch (RuntimeException failure) {
                log.error("Invitation {} was accepted but could not be linked; the next sign-in tries again: {}",
                        candidate.id(), failure.getMessage());
            }
        }
        return userId;
    }

    private List<Candidate> pendingFor(String email) {
        return jdbc.query("""
                SELECT id, organization_id, provider_invitation_id FROM invitation
                WHERE email = ? AND status = 'PENDING' AND provider_invitation_id IS NOT NULL
                ORDER BY created_at
                """, (rs, index) -> new Candidate(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3)), email);
    }

    /** One invitation, in one transaction, in its organization. Empty when someone else got there first. */
    private Optional<Long> link(Candidate candidate, Principal principal, ExternalUserId account, String email,
                                UserProfile profile) {
        List<String> still = jdbc.queryForList("""
                SELECT CAST(employee_details AS TEXT) FROM invitation
                WHERE id = ? AND status = 'PENDING' AND provider_invitation_id = ?
                FOR UPDATE
                """, String.class, candidate.id(), candidate.providerId());
        if (still.isEmpty()) {
            return Optional.empty();
        }
        Long previousOrganization = TenantContext.getCurrentOrgId();
        TenantContext.setCurrentOrgId(candidate.organization());
        try {
            Long userId = userFor(principal, account, email, profile);
            EmployeeJoinOrgDto details = invitations.read(still.get(0));
            EmployeeDto employee = employees.joinOrganization(userId, candidate.organization(), details);
            jdbc.queryForList("SELECT role_id, project_id FROM invitation_role WHERE invitation_id = ? AND organization_id = ?",
                    candidate.id(), candidate.organization())
                    .forEach(role -> assignments.assignAsSystem(employee.getId(), (UUID) role.get("role_id"),
                            role.get("project_id") == null ? null : ((Number) role.get("project_id")).longValue()));
            jdbc.update("""
                    UPDATE invitation SET status = 'ACCEPTED', accepted_at = now(), accepted_user_id = ?, updated_at = now()
                    WHERE id = ?
                    """, userId, candidate.id());
            changes.record(candidate.organization(), userId, ACCEPTED, InvitationService.TARGET,
                    candidate.id().toString(), Map.of("status", InvitationStage.PENDING.name()),
                    Map.of("status", InvitationStage.ACCEPTED.name(), "employee", employee.getId()));
            log.info("Invitation {} accepted: user {} joined organization {} as employee {}",
                    candidate.id(), userId, candidate.organization(), employee.getId());
            return Optional.of(userId);
        } finally {
            TenantContext.setCurrentOrgId(previousOrganization);
        }
    }

    /** The account's Echno user, created with its identity row on a first acceptance. */
    private Long userFor(Principal principal, ExternalUserId account, String email, UserProfile profile) {
        Optional<Long> existing = identities.userId(principal.issuer(), principal.subject());
        if (existing.isPresent()) {
            return existing.get();
        }
        if (users.existsUserByEmail(email)) {
            throw new IllegalStateException("An Echno user with the invited address exists under another account, "
                    + "and an address alone never links one");
        }
        User user = new User();
        user.setName(nameOf(profile, email));
        user.setEmail(email);
        User saved = users.save(user);
        links.linkUser(account, saved.getId());
        return saved.getId();
    }

    private static String nameOf(UserProfile profile, @Nullable String email) {
        return profile.name() != null && !profile.name().isBlank() ? profile.name() : email;
    }
}
