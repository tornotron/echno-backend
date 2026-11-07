package org.tornotron.echno_backend.common.identity.link;

import java.util.HashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.identity.IdentityTokenMapper;
import org.tornotron.echno_backend.common.identity.Principal;

/**
 * Who a signed-in account is, from Echno's own tables: the same {@code ORG_MEMBER_{id}} and
 * {@code ORG_{id}_ROLE_{role}} authorities the guards and {@code TenantFilter} have always read, now
 * minted from {@code employee} and {@code employee_org_roles} instead of the token, plus
 * {@code ROLE_platform-admin} from {@code platform_admin}.
 *
 * <p>Runs once per request, before a tenant exists, so it is one plain statement rather than anything
 * that goes through the tenant-scoped entities. It is deliberately not cached across requests: being
 * read on each one is what makes a removal, a role change or a status change take effect on the next
 * request, whichever writer made it.
 *
 * <p>Employees with status {@code active}, {@code onLeave} or {@code probation} hold their access. Any
 * other status, and null, holds nothing.
 */
@Component
@Slf4j
public class MembershipAuthorities {

    static final String PLATFORM_ADMIN = "ROLE_platform-admin";

    private static final String BY_IDENTITY = "SELECT user_id FROM user_identity WHERE issuer = ? AND subject = ?";
    private static final String OR_BY_OLD_COLUMN = """
             UNION ALL
             SELECT id FROM users_table
             WHERE keycloak_id = ?
               AND NOT EXISTS (SELECT 1 FROM user_identity WHERE issuer = ? AND subject = ?)
            """;
    private static final String READ = """
            WITH me (user_id) AS (%s)
            SELECT me.user_id, e.organization_id, r.role, a.user_id IS NOT NULL AS platform_admin
            FROM me
            LEFT JOIN employee e ON e.user_id = me.user_id AND e.status IN ('active', 'onLeave', 'probation')
            LEFT JOIN employee_org_roles r ON r.employee_id = e.id
            LEFT JOIN platform_admin a ON a.user_id = me.user_id
            """;

    /** The Echno user, or null when the account has none yet, and what they hold. */
    public record Membership(@Nullable Long userId, Set<GrantedAuthority> authorities) {

        public static Membership none() {
            return new Membership(null, Set.of());
        }
    }

    private final JdbcTemplate jdbc;
    private final boolean mayFallBack;

    public MembershipAuthorities(JdbcTemplate jdbc, IdentityTokenMapper mapper) {
        this.jdbc = jdbc;
        this.mayFallBack = mapper.subjectMayBeInKeycloakIdColumn();
    }

    public Membership read(Principal principal) {
        Long[] user = new Long[1];
        Set<GrantedAuthority> authorities = new HashSet<>();
        String issuer = principal.issuer();
        String subject = principal.subject();
        Object[] arguments = mayFallBack
                ? new Object[] {issuer, subject, subject, issuer, subject}
                : new Object[] {issuer, subject};
        String identity = mayFallBack ? BY_IDENTITY + OR_BY_OLD_COLUMN : BY_IDENTITY;
        jdbc.query(READ.formatted(identity), rows -> {
            user[0] = rows.getLong("user_id");
            if (rows.getBoolean("platform_admin")) {
                authorities.add(new SimpleGrantedAuthority(PLATFORM_ADMIN));
            }
            long organization = rows.getLong("organization_id");
            if (!rows.wasNull()) {
                authorities.add(new SimpleGrantedAuthority("ORG_MEMBER_" + organization));
                addRole(authorities, organization, rows.getString("role"));
            }
        }, arguments);
        return new Membership(user[0], authorities);
    }

    private static void addRole(Set<GrantedAuthority> authorities, long organization, @Nullable String role) {
        if (role == null) {
            return;
        }
        try {
            authorities.add(new SimpleGrantedAuthority(
                    "ORG_" + organization + "_ROLE_" + OrgRole.valueOf(role).getGroupName()));
        } catch (IllegalArgumentException unknown) {
            log.warn("Ignoring an unknown role '{}' in organization {}", role, organization);
        }
    }
}
