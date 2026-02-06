package org.tornotron.echno_backend.common.service;

import jakarta.ws.rs.core.Response;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.GroupRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class KeycloakGroupService {

    @Value("${keycloak-initializer.url}")
    private String authServerUrl;

    @Value("${keycloak-initializer.application-realm}")
    private String realm;

    @Value("${keycloak.client-id}")
    private String clientId;

    @Value("${keycloak.secret}")
    private String clientSecret;

    public String createOrganizationGroup(String organizationId, String organizationName) {
        Keycloak keycloak = getKeycloakAdminClient();

        GroupRepresentation group = new GroupRepresentation();
        group.setName("org-" + organizationId);
        group.singleAttribute("organizationId", organizationId);
        group.singleAttribute("organizationName", organizationName);

        Response response = keycloak.realm(realm).groups().add(group);

        String locationHeader = response.getHeaderString("Location");
        String groupId = locationHeader.substring(locationHeader.lastIndexOf('/')+1);

        response.close();
        keycloak.close();

        return groupId;
    }

    public void addUserToOrganization(String userId, String organizationId) {
        Keycloak keycloak = getKeycloakAdminClient();

        List<GroupRepresentation> groups = keycloak.realm(realm).groups()
                .groups("org-"+organizationId,0,1);

        if(!groups.isEmpty()) {
            String groupId = groups.getFirst().getId();
            keycloak.realm(realm).users().get(userId).joinGroup(groupId);
        }

        keycloak.close();
    }

    public void removeUserFromOrganization(String userId, String organizationId) {
        Keycloak keycloak = getKeycloakAdminClient();

        List<GroupRepresentation> groups = keycloak.realm(realm).groups()
                .groups("org-" + organizationId, 0, 1);

        if (!groups.isEmpty()) {
            String groupId = groups.getFirst().getId();
            keycloak.realm(realm).users().get(userId).leaveGroup(groupId);
        }

        keycloak.close();
    }

    public void deleteOrganizationGroup(String organizationId) {
        Keycloak keycloak = getKeycloakAdminClient();

        List<GroupRepresentation> groups = keycloak.realm(realm).groups()
                .groups("org-" + organizationId, 0, 1);

        if (!groups.isEmpty()) {
            String groupId = groups.getFirst().getId();
            keycloak.realm(realm).groups().group(groupId).remove();
        }

        keycloak.close();
    }

    public List<String> getUserOrganizations(String userId) {
        Keycloak keycloak = getKeycloakAdminClient();

        List<GroupRepresentation> userGroups = keycloak.realm(realm)
                .users().get(userId).groups();

        List<String> organizationIds = userGroups.stream()
                .filter(g -> g.getName().startsWith("org-"))
                .map(g -> g.getName().substring(4)) // Remove "org-" prefix
                .toList();

        keycloak.close();
        return organizationIds;
    }

    private Keycloak getKeycloakAdminClient() {
        return KeycloakBuilder.builder()
                .serverUrl(authServerUrl)
                .realm(realm)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .grantType("client_credentials")
                .build();
    }
}
