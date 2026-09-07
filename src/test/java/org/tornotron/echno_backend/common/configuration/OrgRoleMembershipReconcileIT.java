package org.tornotron.echno_backend.common.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that the startup reconcile <b>changes nothing</b> when it finds an org role held without
 * membership of that organization, against a real Keycloak.
 *
 * <p>This is the correction of a mistake that was merged and caught before it deployed. The first
 * version of the reconcile added the missing membership, on the reasoning that membership is the
 * weaker of the two authorities so granting it could only widen access to an organization the user
 * was already recorded as an administrator of. That reasoning had the population backwards.
 *
 * <p>The split state is not mainly produced by provisioning. It is produced by <b>offboarding</b>:
 * before the fix in {@code KeycloakGroupService}, {@code EmployeeService.deleteAnEmployee} left the
 * parent group and stopped, and Keycloak keeps the two memberships as independent rows, so a
 * removed employee kept {@code ORG_5_ROLE_system-admin} and lost only {@code ORG_MEMBER_5}. The
 * provisioning case is not reachable through the application at all, because {@code assignOrgRole}
 * resolves the employee against the tenant first and {@code joinOrganization} writes the employee
 * row and the group membership in one transaction. So a reconcile that adds membership would have
 * handed every offboarded administrator their organization back, silently, on the next startup.
 *
 * <p>The two situations are byte-identical in Keycloak, and nothing in the realm separates them:
 * offboarding hard-deletes the employee row, leaves the account enabled, and records nothing. The
 * application database does separate them and still cannot be read here, because the QA seed
 * imports the realm before it restores the database, so a backend starting between the halves
 * would read every seeded role holder as offboarded.
 *
 * <p>Since a reconcile cannot choose correctly, it does not choose. These tests pin that: both
 * directions of change are asserted absent, so neither a re-added membership nor an automatic role
 * removal can be reintroduced without a failure here.
 */
class OrgRoleMembershipReconcileIT {

    private static final String TEST_REALM = "echno-reconcile-it-realm";
    private static final String ORG_GROUP = "org-5";
    private static final String ROLE_SUBGROUP = "system-admin";
    private static final String ORG_GROUP_PATH = "/" + ORG_GROUP;
    private static final String ROLE_SUBGROUP_PATH = ORG_GROUP_PATH + "/" + ROLE_SUBGROUP;

    private static final KeycloakContainer KEYCLOAK =
            new KeycloakContainer("quay.io/keycloak/keycloak:26.0.7");

    private static Keycloak master;
    private static KeycloakInitializer initializer;

    /** In the role subgroup only, which is what an offboarding used to leave behind. */
    private static String splitUserId;
    /** In both groups, the ordinary state, present so the check is shown not to disturb it. */
    private static String properUserId;

    @BeforeAll
    static void setUp() {
        KEYCLOAK.start();

        master = KeycloakBuilder.builder()
                .serverUrl(KEYCLOAK.getAuthServerUrl())
                .realm("master")
                .clientId("admin-cli")
                .username(KEYCLOAK.getAdminUsername())
                .password(KEYCLOAK.getAdminPassword())
                .grantType(OAuth2Constants.PASSWORD)
                .build();

        RealmRepresentation realm = new RealmRepresentation();
        realm.setRealm(TEST_REALM);
        realm.setEnabled(true);
        master.realms().create(realm);

        String orgGroupId = createGroup();
        String roleSubgroupId = createRoleSubgroup(orgGroupId);

        splitUserId = createUser("offboarded-admin");
        master.realm(TEST_REALM).users().get(splitUserId).joinGroup(roleSubgroupId);

        properUserId = createUser("current-admin");
        master.realm(TEST_REALM).users().get(properUserId).joinGroup(orgGroupId);
        master.realm(TEST_REALM).users().get(properUserId).joinGroup(roleSubgroupId);

        initializer = newInitializer();
        runTheReconcile();
    }

    @AfterAll
    static void tearDown() {
        if (master != null) {
            master.close();
        }
        KEYCLOAK.stop();
    }

    @Test
    void theReconcileDoesNotAddTheMissingMembership() {
        // The whole point. Adding it here would restore an offboarded administrator's access to
        // the organization they were removed from.
        assertThat(groupPathsOf(splitUserId)).doesNotContain(ORG_GROUP_PATH);
    }

    @Test
    void theReconcileDoesNotRemoveTheRoleEither() {
        // The opposite repair is equally unsafe to automate: on the reading where the user was
        // never given membership, removing the role takes away access they should have.
        assertThat(groupPathsOf(splitUserId)).contains(ROLE_SUBGROUP_PATH);
    }

    @Test
    void theSplitUserIsLeftInExactlyTheStateTheyWereFoundIn() {
        assertThat(groupPathsOf(splitUserId)).containsExactly(ROLE_SUBGROUP_PATH);
    }

    @Test
    void anOrdinaryRoleHolderIsNotDisturbed() {
        assertThat(groupPathsOf(properUserId))
                .containsExactlyInAnyOrder(ORG_GROUP_PATH, ROLE_SUBGROUP_PATH);
    }

    /** Invokes the private reconcile directly, so the test covers it and not the whole startup. */
    private static void runTheReconcile() {
        ReflectionTestUtils.invokeMethod(initializer, "reportOrgRoleHoldersWithoutMembership");
    }

    private static List<String> groupPathsOf(String userId) {
        return master.realm(TEST_REALM).users().get(userId).groups().stream()
                .map(GroupRepresentation::getPath)
                .toList();
    }

    private static KeycloakInitializer newInitializer() {
        KeycloakInitializerConfigurationProperties props = new KeycloakInitializerConfigurationProperties();
        props.setMasterRealm("master");
        props.setApplicationRealm(TEST_REALM);
        props.setClientId("admin-cli");
        props.setUsername(KEYCLOAK.getAdminUsername());
        props.setPassword(KEYCLOAK.getAdminPassword());
        props.setUrl(KEYCLOAK.getAuthServerUrl());

        KeycloakInitializer built = new KeycloakInitializer(
                master, props, new ObjectMapper(),
                Mockito.mock(KeycloakConfig.class), Mockito.mock(DevFixtureProvisioner.class));

        // REALM_ID is static and normally set from ApplicationReadyEvent; the reconcile reads it
        // and the admin client, so both are set directly here.
        ReflectionTestUtils.setField(KeycloakInitializer.class, "REALM_ID", TEST_REALM);
        ReflectionTestUtils.setField(built, "admin", master);
        return built;
    }

    private static String createGroup() {
        GroupRepresentation group = new GroupRepresentation();
        group.setName(ORG_GROUP);
        try (Response response = master.realm(TEST_REALM).groups().add(group)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private static String createRoleSubgroup(String orgGroupId) {
        GroupRepresentation subgroup = new GroupRepresentation();
        subgroup.setName(ROLE_SUBGROUP);
        try (Response response = master.realm(TEST_REALM).groups().group(orgGroupId).subGroup(subgroup)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private static String createUser(String username) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEnabled(true);
        try (Response response = master.realm(TEST_REALM).users().create(user)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }
}
