package org.tornotron.echno_backend.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.tornotron.echno_backend.common.identity.IdentityTokenMapper;
import org.tornotron.echno_backend.common.identity.keycloak.KeycloakTokenMapper;
import org.tornotron.echno_backend.common.identity.link.UserIdentityLookup;

/**
 * What finding a user by their account needs in a JPA slice, so the many slices that import
 * {@code UserContextService} or {@code EmployeeService} do not each have to list it. Registered for
 * every {@code @DataJpaTest} through {@code META-INF/spring}, and a test configuration, so a full
 * context does not pick it up by scanning. A slice that declares its own wins.
 */
@TestConfiguration(proxyBeanMethods = false)
public class IdentitySliceConfiguration {

    @Bean
    @ConditionalOnMissingBean
    IdentityTokenMapper identityTokenMapper() {
        return new KeycloakTokenMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    UserIdentityLookup userIdentityLookup(JdbcTemplate jdbc, IdentityTokenMapper mapper) {
        return new UserIdentityLookup(jdbc, mapper);
    }
}
