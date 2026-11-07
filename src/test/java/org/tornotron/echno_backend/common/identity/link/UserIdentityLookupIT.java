package org.tornotron.echno_backend.common.identity.link;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.tornotron.echno_backend.common.identity.ExternalUserId;
import org.tornotron.echno_backend.common.identity.IdentityTokenMapper;
import org.tornotron.echno_backend.common.identity.Principal;
import org.tornotron.echno_backend.common.identity.keycloak.KeycloakTokenMapper;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;
import org.springframework.security.oauth2.jwt.Jwt;

/** Identity reads and writes on CockroachDB, with and without the old column to fall back on. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(KeycloakTokenMapper.class)
class UserIdentityLookupIT extends AbstractIntegrationTest {

    /** A provider that never wrote the old column. */
    private static final IdentityTokenMapper OTHER_PROVIDER = new IdentityTokenMapper() {
        @Override
        public Principal toPrincipal(Jwt token) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String provider() {
            return "other";
        }

        @Override
        public String issuer() {
            return "https://other.example.test";
        }
    };

    @Autowired
    private TestEntityManager em;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KeycloakTokenMapper keycloak;

    @Autowired
    private UserIdentityRepository users;

    @Autowired
    private OrganizationIdentityRepository organizations;

    private User user(String keycloakId) {
        User user = new User();
        user.setName("Person");
        user.setKeycloakId(keycloakId);
        return em.persistAndFlush(user);
    }

    @Test
    void aLinkedAccountResolvesWithNoOldColumnAtAll() {
        User person = user(null);
        new IdentityLinks(users, organizations, keycloak).linkUser(new ExternalUserId("linked-subject"), person.getId());

        UserIdentityLookup lookup = new UserIdentityLookup(jdbc, keycloak);

        assertThat(lookup.userId("linked-subject")).contains(person.getId());
        assertThat(lookup.accountOf(person)).contains(new ExternalUserId("linked-subject"));
    }

    @Test
    void underKeycloakAUserTheBackfillMissedStillResolves() {
        User person = user("only-in-the-old-column");

        UserIdentityLookup lookup = new UserIdentityLookup(jdbc, keycloak);

        assertThat(lookup.userId("only-in-the-old-column")).contains(person.getId());
        assertThat(lookup.accountOf(person)).contains(new ExternalUserId("only-in-the-old-column"));
    }

    @Test
    void anotherProviderNeverLooksInTheOldColumn() {
        User person = user("only-in-the-old-column-2");

        UserIdentityLookup lookup = new UserIdentityLookup(jdbc, OTHER_PROVIDER);

        assertThat(lookup.userId("only-in-the-old-column-2")).isEmpty();
        assertThat(lookup.accountOf(person)).isEmpty();
    }

    @Test
    void theSameSubjectUnderTwoIssuersIsTwoAccounts() {
        User first = user(null);
        User second = user(null);
        new IdentityLinks(users, organizations, keycloak).linkUser(new ExternalUserId("shared-subject"), first.getId());
        new IdentityLinks(users, organizations, OTHER_PROVIDER).linkUser(new ExternalUserId("shared-subject"), second.getId());

        assertThat(new UserIdentityLookup(jdbc, keycloak).userId("shared-subject")).contains(first.getId());
        assertThat(new UserIdentityLookup(jdbc, OTHER_PROVIDER).userId("shared-subject")).contains(second.getId());
    }

    @Test
    void writingALinkAgainChangesNothing() {
        User person = user(null);
        IdentityLinks links = new IdentityLinks(users, organizations, keycloak);

        links.linkUser(new ExternalUserId("repeat-subject"), person.getId());
        links.linkUser(new ExternalUserId("repeat-subject"), person.getId());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_identity WHERE user_id = ?",
                Integer.class, person.getId())).isEqualTo(1);
    }
}
