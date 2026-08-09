package org.tornotron.echno_backend.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test proving the Testcontainers + Liquibase + JPA stack end to end:
 * a real CockroachDB is provisioned, the changelog builds the schema, and the
 * org-scoped user query (added to close the cross-tenant user dump) runs as real
 * SQL. Subsequent tenant-isolation tests build on this harness.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void findUsersByOrganizationId_executesAndReturnsEmpty_forAnUnknownOrg() {
        Page<User> result = userRepository.findUsersByOrganizationId(999_999L, PageRequest.of(0, 20));

        assertThat(result.getContent()).isEmpty();
    }
}
