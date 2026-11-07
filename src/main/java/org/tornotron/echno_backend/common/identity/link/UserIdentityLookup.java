package org.tornotron.echno_backend.common.identity.link;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.identity.ExternalUserId;
import org.tornotron.echno_backend.common.identity.IdentityTokenMapper;
import org.tornotron.echno_backend.user.User;

/**
 * Finds the Echno user behind a provider's account, and the account behind an Echno user. Both are
 * plain reads, so they work with no tenant in place, which is where a token is first looked at.
 *
 * <p>While a provider's subjects may still sit in {@code users_table.keycloak_id} only, because the
 * backfill has not finished or could not run, a miss falls back to that column, so a boot that lost
 * the backfill costs nobody their account. {@link IdentityTokenMapper#subjectMayBeInKeycloakIdColumn()}
 * says whether it applies. The fallback goes with the column at the build plan's step 33.
 */
@Component
public class UserIdentityLookup {

    private final JdbcTemplate jdbc;
    private final IdentityTokenMapper mapper;

    public UserIdentityLookup(JdbcTemplate jdbc, IdentityTokenMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** The Echno user this account is, if there is one. */
    public Optional<Long> userId(String issuer, String subject) {
        List<Long> linked = jdbc.queryForList(
                "SELECT user_id FROM user_identity WHERE issuer = ? AND subject = ?", Long.class, issuer, subject);
        if (!linked.isEmpty()) {
            return Optional.of(linked.getFirst());
        }
        if (!mapper.subjectMayBeInKeycloakIdColumn()) {
            return Optional.empty();
        }
        return jdbc.queryForList("SELECT id FROM users_table WHERE keycloak_id = ?", Long.class, subject)
                .stream().findFirst();
    }

    /** The Echno user behind a subject of the running provider. */
    public Optional<Long> userId(String subject) {
        return userId(mapper.issuer(), subject);
    }

    /** The user's account at the running provider, if they have one. */
    public Optional<ExternalUserId> accountOf(User user) {
        List<String> linked = jdbc.queryForList(
                "SELECT subject FROM user_identity WHERE user_id = ? AND issuer = ?",
                String.class, user.getId(), mapper.issuer());
        if (!linked.isEmpty()) {
            return Optional.of(new ExternalUserId(linked.getFirst()));
        }
        if (mapper.subjectMayBeInKeycloakIdColumn() && user.getKeycloakId() != null) {
            return Optional.of(new ExternalUserId(user.getKeycloakId()));
        }
        return Optional.empty();
    }
}
