package org.tornotron.echno_backend.common.identity.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.security.oauth2.jwt.Jwt;
import org.tornotron.echno_backend.common.startup.StartupTaskState;

class KeycloakTokenGroupsTest {

    private static final List<String> GROUPS = List.of("/org-5", "/org-5/system-admin", "/org-50", "/org-50/hr-admin");

    private final StartupTaskState state = mock(StartupTaskState.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final KeycloakTokenGroups groups = new KeycloakTokenGroups(state, jdbc);

    private static Jwt token(String username) {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject(username)
                .claim("preferred_username", username)
                .claim("groups", GROUPS)
                .build();
    }

    private void importFinished(boolean finished) {
        when(state.hasCompleted(KeycloakRoleImport.NAME)).thenReturn(finished);
    }

    private void recorded(String subject, long organization) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString(1)).thenReturn(subject);
        when(row.getLong(2)).thenReturn(organization);
        doAnswer(call -> {
            call.<RowCallbackHandler>getArgument(1).processRow(row);
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class));
    }

    @Test
    void aMachineLoginAlwaysKeepsItsGroups() {
        importFinished(true);

        assertThat(groups.stillHonoured(token("service-account-camera"))).isEqualTo(GROUPS);
    }

    @Test
    void aPersonsGroupsCountWhileTheImportHasNotFinished() {
        importFinished(false);

        assertThat(groups.stillHonoured(token("asha"))).isEqualTo(GROUPS);
    }

    @Test
    void aPersonsGroupsStopCountingOnceItHas() {
        importFinished(true);

        assertThat(groups.stillHonoured(token("asha"))).isEmpty();
    }

    @Test
    void anAccountTheImportRecordedKeepsOnlyThatOrganizationsGroups() throws Exception {
        importFinished(true);
        recorded("bootstrap-admin", 5);

        assertThat(groups.stillHonoured(token("bootstrap-admin"))).containsExactly("/org-5", "/org-5/system-admin");
        assertThat(groups.stillHonoured(token("asha"))).isEmpty();
    }

    @Test
    void aFinishedImportAndTheRecordedAccountsAreNotAskedAboutOnEveryRequest() {
        importFinished(true);

        groups.stillHonoured(token("asha"));
        groups.stillHonoured(token("asha"));

        verify(state, times(1)).hasCompleted(KeycloakRoleImport.NAME);
        verify(jdbc, times(1)).query(anyString(), any(RowCallbackHandler.class));
    }

    @Test
    void anUnfinishedImportIsNotAskedAboutOnEveryRequest() {
        importFinished(false);

        groups.stillHonoured(token("asha"));
        groups.stillHonoured(token("asha"));

        verify(state, times(1)).hasCompleted(KeycloakRoleImport.NAME);
    }

    @Test
    void aTokenWithNoGroupsHasNone() {
        importFinished(false);

        assertThat(groups.stillHonoured(Jwt.withTokenValue("t").header("alg", "RS256").subject("s").build())).isEmpty();
    }
}
