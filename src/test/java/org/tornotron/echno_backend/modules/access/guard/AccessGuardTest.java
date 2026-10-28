package org.tornotron.echno_backend.modules.access.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;

/**
 * {@code @access} in a {@code @PreAuthorize} expression asks the access service for the current
 * request's subject, and refuses when there is no subject.
 */
@SpringJUnitConfig(AccessGuardTest.Config.class)
@WithMockUser
class AccessGuardTest {

    private static final AccessSubject ALICE = new AccessSubject(7L, 1L, 11L);

    @Configuration
    @EnableMethodSecurity
    static class Config {

        @Bean
        CurrentAccessSubject currentAccessSubject() {
            return mock(CurrentAccessSubject.class);
        }

        @Bean
        AccessService accessService() {
            return mock(AccessService.class);
        }

        @Bean("access")
        AccessGuard access(CurrentAccessSubject current, AccessService service) {
            return new AccessGuard(current, service);
        }

        @Bean
        Guarded guarded() {
            return new Guarded();
        }
    }

    static class Guarded {

        @PreAuthorize("@access.can('wbs', 'edit')")
        public String editWbs() {
            return "edited";
        }

        @PreAuthorize("@access.isMember()")
        public String readNotices() {
            return "read";
        }
    }

    @Autowired
    private Guarded guarded;

    @Autowired
    private CurrentAccessSubject current;

    @Autowired
    private AccessService access;

    @BeforeEach
    void subjectIsAlice() {
        reset(current, access);
        when(current.resolve()).thenReturn(Optional.of(ALICE));
    }

    @Test
    void canAllowsWhatTheServiceAllows() {
        when(access.check(ALICE, AccessPermission.of("wbs:edit"))).thenReturn(true);

        assertThat(guarded.editWbs()).isEqualTo("edited");
    }

    @Test
    void canRefusesWhatTheServiceRefuses() {
        when(access.check(ALICE, AccessPermission.of("wbs:edit"))).thenReturn(false);

        assertThatThrownBy(guarded::editWbs).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void isMemberFollowsTheService() {
        when(access.isActiveMember(ALICE)).thenReturn(true);
        assertThat(guarded.readNotices()).isEqualTo("read");

        when(access.isActiveMember(ALICE)).thenReturn(false);
        assertThatThrownBy(guarded::readNotices).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void everythingIsRefusedWithoutASubject() {
        when(current.resolve()).thenReturn(Optional.empty());
        when(access.check(ALICE, AccessPermission.of("wbs:edit"))).thenReturn(true);
        when(access.isActiveMember(ALICE)).thenReturn(true);

        assertThatThrownBy(guarded::editWbs).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(guarded::readNotices).isInstanceOf(AccessDeniedException.class);
    }
}
