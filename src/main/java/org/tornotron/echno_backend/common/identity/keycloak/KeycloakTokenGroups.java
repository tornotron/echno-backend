package org.tornotron.echno_backend.common.identity.keycloak;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.identity.ConditionalOnKeycloak;
import org.tornotron.echno_backend.common.identity.TokenGroups;
import org.tornotron.echno_backend.common.startup.StartupTaskState;

/**
 * Which Keycloak tokens still have their groups honoured, so that moving the truth to Echno's tables
 * costs nobody their access.
 *
 * <ul>
 *   <li>A machine login has no employee record, so its groups are all it has, and
 *       {@code observation-producer} depends on them.
 *   <li>A person's groups count until {@link KeycloakRoleImport} has finished once on this database.
 *       Before that {@code employee_org_roles} may be short of a role only Keycloak knows about, and a
 *       failed import must not cost anyone access. It means a stopped employee keeps access for that
 *       long, which is no worse than today.
 *   <li>After that, a person's groups count only for an organization where the import found them with
 *       no employee, such as a bootstrap administrator, who has no row for the tables to read.
 * </ul>
 *
 * <p>Neither answer comes from a query per request: the import's finishing is remembered once seen, and
 * the short list of recorded accounts is held in memory and refreshed every so often.
 */
@Component
@ConditionalOnKeycloak
public class KeycloakTokenGroups implements TokenGroups {

    private static final String SERVICE_ACCOUNT_PREFIX = "service-account-";
    private static final String GROUPS_CLAIM = "groups";
    private static final Duration RECHECK = Duration.ofSeconds(30);

    private final StartupTaskState state;
    private final JdbcTemplate jdbc;

    private volatile boolean importFinished;
    private volatile Instant nextImportCheck = Instant.EPOCH;
    private volatile Map<String, Set<Long>> groupOnlyMembers = Map.of();
    private volatile Instant nextMemberRead = Instant.EPOCH;

    public KeycloakTokenGroups(StartupTaskState state, JdbcTemplate jdbc) {
        this.state = state;
        this.jdbc = jdbc;
    }

    @Override
    public List<String> stillHonoured(Jwt token) {
        List<String> groups = token.getClaimAsStringList(GROUPS_CLAIM);
        if (groups == null) {
            return List.of();
        }
        if (isMachineLogin(token) || !importHasFinished()) {
            return groups;
        }
        Set<Long> organizations = groupOnlyMembers().get(token.getSubject());
        if (organizations == null) {
            return List.of();
        }
        return groups.stream().filter(group -> belongsToOneOf(group, organizations)).toList();
    }

    private static boolean isMachineLogin(Jwt token) {
        String username = token.getClaimAsString("preferred_username");
        return username != null && username.startsWith(SERVICE_ACCOUNT_PREFIX);
    }

    /** Once true it stays true, so a finished database costs no query at all. */
    private boolean importHasFinished() {
        if (importFinished) {
            return true;
        }
        Instant now = Instant.now();
        if (now.isBefore(nextImportCheck)) {
            return false;
        }
        nextImportCheck = now.plus(RECHECK);
        importFinished = state.hasCompleted(KeycloakRoleImport.NAME);
        return importFinished;
    }

    private Map<String, Set<Long>> groupOnlyMembers() {
        Instant now = Instant.now();
        if (now.isBefore(nextMemberRead)) {
            return groupOnlyMembers;
        }
        nextMemberRead = now.plus(RECHECK);
        Map<String, Set<Long>> read = new HashMap<>();
        jdbc.query("SELECT subject, organization_id FROM keycloak_group_only_member", rows -> {
            read.computeIfAbsent(rows.getString(1), subject -> new HashSet<>()).add(rows.getLong(2));
        });
        groupOnlyMembers = read;
        return read;
    }

    /** {@code /org-5} and {@code /org-5/system-admin} both belong to organization 5. */
    private static boolean belongsToOneOf(String group, Set<Long> organizations) {
        String path = group.startsWith("/") ? group.substring(1) : group;
        if (!path.startsWith(KeycloakIdentityProvider.GROUP_PREFIX)) {
            return false;
        }
        String rest = path.substring(KeycloakIdentityProvider.GROUP_PREFIX.length());
        int slash = rest.indexOf('/');
        try {
            return organizations.contains(Long.parseLong(slash < 0 ? rest : rest.substring(0, slash)));
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
