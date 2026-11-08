package org.tornotron.echno_backend.auth.authority;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.auth.AuthProperties;
import org.tornotron.echno_backend.auth.AuthenticatedIdentity;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * {@code ROLE_platform-admin} for the subjects listed in {@code echno.auth.platform-admin-subjects}.
 *
 * <p>Platform staff are not an organization role. The catalogue they edit is shared by every
 * tenant, so no tenant's own system-admin may hold it, and it is granted here, per deployment, by
 * the people who run the deployment. A subject rather than an email, because a subject is the one
 * value the provider guarantees is this account and no other.
 */
@Slf4j
@Component
public class PlatformAdminAuthorityProvider implements AuthorityProvider {

    public static final String PLATFORM_ADMIN = "ROLE_platform-admin";

    private static final List<GrantedAuthority> GRANTED = List.of(new SimpleGrantedAuthority(PLATFORM_ADMIN));

    private final Set<String> subjects;

    public PlatformAdminAuthorityProvider(AuthProperties properties) {
        this.subjects = Set.copyOf(properties.platformAdminSubjects());
        log.info("{} platform admin subject(s) configured", subjects.size());
    }

    @Override
    public Collection<? extends GrantedAuthority> authoritiesFor(AuthenticatedIdentity identity) {
        return subjects.contains(identity.subject()) ? GRANTED : List.of();
    }
}
