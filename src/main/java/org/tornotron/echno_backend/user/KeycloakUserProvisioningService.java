package org.tornotron.echno_backend.user;

import jakarta.ws.rs.core.Response;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.exception.DatabaseOperationException;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;

import java.util.Collections;
import java.util.List;

/**
 * Provisions the Keycloak identity backing an application user: create (with an
 * initial password and the default {@code user} realm role), delete, and an
 * existence check.
 *
 * These are the low-level Keycloak primitives extracted from {@link UserService}.
 * The registration saga (create Keycloak user, persist the local row, and on a
 * database failure roll the Keycloak user back) stays in {@link UserService};
 * this service only exposes the individual operations.
 */
@Service
public class KeycloakUserProvisioningService {

    private static final Logger logger = LoggerFactory.getLogger(KeycloakUserProvisioningService.class);

    private final Keycloak keycloak;

    @Value("${keycloak-initializer.application-realm}")
    private String realm;

    public KeycloakUserProvisioningService(Keycloak keycloak) {
        this.keycloak = keycloak;
    }

    /**
     * Creates a Keycloak user with the given credentials, sets a permanent
     * password, and best-effort assigns the default {@code user} realm role.
     *
     * @return the new Keycloak user id.
     * @throws DuplicateResourceException if a user with the email already exists.
     */
    public String createUser(String username, String email, String password) {
        if (userExists(email)) {
            throw new DuplicateResourceException("User with email '" + email + "' already exists in Keycloak");
        }

        UsersResource usersResource = keycloak.realm(realm).users();

        UserRepresentation userRepresentation = new UserRepresentation();
        userRepresentation.setUsername(username);
        userRepresentation.setEmail(email);
        userRepresentation.setEnabled(true);
        userRepresentation.setEmailVerified(true);

        String keycloakId;
        try (Response response = usersResource.create(userRepresentation)) {
            if (response.getStatus() != 201) {
                throw new RuntimeException("Failed to create user '" + username + "' in Keycloak (status " + response.getStatus() + ")");
            }
            String location = response.getLocation().toString();
            keycloakId = location.substring(location.lastIndexOf('/') + 1);
        }

        CredentialRepresentation credentialRepresentation = new CredentialRepresentation();
        credentialRepresentation.setType(CredentialRepresentation.PASSWORD);
        credentialRepresentation.setValue(password);
        credentialRepresentation.setTemporary(false);

        usersResource.get(keycloakId).resetPassword(credentialRepresentation);

        try {
            var userRole = keycloak.realm(realm).roles().get("user").toRepresentation();
            usersResource.get(keycloakId).roles().realmLevel().add(Collections.singletonList(userRole));
        } catch (Exception e) {
            logger.warn("Failed to assign 'user' realm role to new user {}: {}", keycloakId, e.getMessage());
        }

        return keycloakId;
    }

    /**
     * Removes a Keycloak user. Used as the compensating action when the local
     * database write fails after the Keycloak user was created. Never throws;
     * failures are logged for manual follow-up.
     */
    public void deleteUser(String keycloakId) {
        try {
            if (keycloakId != null) {
                keycloak.realm(realm).users().get(keycloakId).remove();
                logger.info("Rolled back Keycloak user creation for ID: {}", keycloakId);
            }
        } catch (Exception e) {
            logger.error("Failed to rollback Keycloak user creation for ID: {}. Manual intervention required.", keycloakId, e);
        }
    }

    private boolean userExists(String email) {
        try {
            UsersResource usersResource = keycloak.realm(realm).users();
            List<UserRepresentation> users = usersResource.search(email, true);
            return !users.isEmpty();
        } catch (Exception ex) {
            throw new DatabaseOperationException("Failed to check whether user with email '" + email + "' exists in Keycloak: " + ex.getMessage());
        }
    }
}
