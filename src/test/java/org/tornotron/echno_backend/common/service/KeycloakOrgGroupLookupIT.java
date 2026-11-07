package org.tornotron.echno_backend.common.service;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.test.util.ReflectionTestUtils;
import org.tornotron.echno_backend.common.enums.OrgRole;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An organization whose group is missing must never be handed a group whose name merely contains its
 * own. Each write that resolves an organization's group runs here with {@code org-5} missing and
 * {@code org-50} present, against a real Keycloak, because the near match came from Keycloak's search.
 */
class KeycloakOrgGroupLookupIT {

    private static final String REALM = "echno-group-lookup-it-realm";
    private static final String BACKEND_CLIENT_ID = "echno-backend";
    // Placeholder for a container that lives for the length of this class, not a real credential.
    private static final String BACKEND_CLIENT_SECRET = "echno-backend-lookup-it-secret";
    private static final String MISSING_ORG = "5";
    private static final String OTHER_ORG = "50";
    private static final String OTHER_GROUP = "/org-" + OTHER_ORG;

    // Pinned to the tag matching the keycloak-admin-client version, as the other Keycloak ITs are.
    private static final KeycloakContainer KEYCLOAK =
            new KeycloakContainer("quay.io/keycloak/keycloak:26.0.7");

    private static Keycloak master;
    private static KeycloakGroupService groupService;

    private String userId;

    @BeforeAll
    static void startKeycloak() {
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
        realm.setRealm(REALM);
        realm.setEnabled(true);
        master.realms().create(realm);
        createBackendServiceAccount();

        groupService = new KeycloakGroupService();
        ReflectionTestUtils.setField(groupService, "authServerUrl", KEYCLOAK.getAuthServerUrl());
        ReflectionTestUtils.setField(groupService, "realm", REALM);
        ReflectionTestUtils.setField(groupService, "clientId", BACKEND_CLIENT_ID);
        ReflectionTestUtils.setField(groupService, "clientSecret", BACKEND_CLIENT_SECRET);
    }

    @AfterAll
    static void stopKeycloak() {
        if (master != null) {
            master.close();
        }
        KEYCLOAK.stop();
    }

    @BeforeEach
    void startWithOnlyTheOtherOrganizationsGroup() {
        master.realm(REALM).groups().groups().forEach(
                group -> master.realm(REALM).groups().group(group.getId()).remove());
        groupService.createOrganizationGroup(OTHER_ORG, "Org Fifty");

        UserRepresentation user = new UserRepresentation();
        user.setUsername("person-" + System.nanoTime());
        user.setEnabled(true);
        try (Response response = master.realm(REALM).users().create(user)) {
            userId = CreatedResponseUtil.getCreatedId(response);
        }
    }

    @Test
    void addingAMemberIsRefusedAndTheOtherGroupGainsNobody() {
        assertThatThrownBy(() -> groupService.addUserToOrganization(userId, MISSING_ORG))
                .hasMessageContaining("org-5");

        assertThat(groupPaths()).isEmpty();
    }

    @Test
    void assigningARoleIsRefusedAndTheOtherGroupGainsNobody() {
        assertThatThrownBy(() -> groupService.assignOrgRole(userId, MISSING_ORG, OrgRole.SYSTEM_ADMIN))
                .hasMessageContaining("org-5");

        assertThat(groupPaths()).isEmpty();
    }

    @Test
    void removingAMemberLeavesTheirMembershipOfTheOtherOrganization() {
        groupService.assignOrgRole(userId, OTHER_ORG, OrgRole.SYSTEM_ADMIN);

        groupService.removeUserFromOrganization(userId, MISSING_ORG);

        assertThat(groupPaths()).contains(OTHER_GROUP, OTHER_GROUP + "/system-admin");
    }

    @Test
    void removingARoleIsRefusedAndLeavesTheOtherOrganizationsRole() {
        groupService.assignOrgRole(userId, OTHER_ORG, OrgRole.SYSTEM_ADMIN);

        assertThatThrownBy(() -> groupService.removeOrgRole(userId, MISSING_ORG, OrgRole.SYSTEM_ADMIN))
                .hasMessageContaining("org-5");

        assertThat(groupPaths()).contains(OTHER_GROUP, OTHER_GROUP + "/system-admin");
    }

    @Test
    void deletingTheMissingGroupLeavesTheOtherGroupStanding() {
        groupService.deleteOrganizationGroup(MISSING_ORG);

        assertThat(master.realm(REALM).groups().groups()).extracting(GroupRepresentation::getName)
                .contains("org-" + OTHER_ORG);
    }

    @Test
    void withBothGroupsPresentEachWriteLandsOnItsOwnGroup() {
        groupService.createOrganizationGroup(MISSING_ORG, "Org Five");

        groupService.addUserToOrganization(userId, MISSING_ORG);

        assertThat(groupPaths()).containsExactly("/org-" + MISSING_ORG);
    }

    @Test
    void aSubgroupCarryingTheNameIsNotTheOrganizationsGroup() {
        GroupRepresentation nested = new GroupRepresentation();
        nested.setName("org-" + MISSING_ORG);
        String otherGroupId = master.realm(REALM).groups().groups("org-" + OTHER_ORG, true, 0, 1, true).getFirst().getId();
        master.realm(REALM).groups().group(otherGroupId).subGroup(nested).close();

        assertThatThrownBy(() -> groupService.addUserToOrganization(userId, MISSING_ORG))
                .hasMessageContaining("org-5");

        assertThat(groupPaths()).isEmpty();
    }

    private List<String> groupPaths() {
        return master.realm(REALM).users().get(userId).groups().stream()
                .map(GroupRepresentation::getPath)
                .toList();
    }

    private static void createBackendServiceAccount() {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(BACKEND_CLIENT_ID);
        client.setEnabled(true);
        client.setPublicClient(false);
        client.setServiceAccountsEnabled(true);
        client.setSecret(BACKEND_CLIENT_SECRET);
        client.setStandardFlowEnabled(false);

        String clientUuid;
        try (Response response = master.realm(REALM).clients().create(client)) {
            clientUuid = CreatedResponseUtil.getCreatedId(response);
        }

        UserRepresentation serviceAccount = master.realm(REALM).clients().get(clientUuid).getServiceAccountUser();
        String realmManagement = master.realm(REALM).clients().findByClientId("realm-management").getFirst().getId();
        RoleRepresentation realmAdmin = master.realm(REALM).clients().get(realmManagement)
                .roles().get("realm-admin").toRepresentation();
        master.realm(REALM).users().get(serviceAccount.getId()).roles().clientLevel(realmManagement).add(List.of(realmAdmin));
    }
}
