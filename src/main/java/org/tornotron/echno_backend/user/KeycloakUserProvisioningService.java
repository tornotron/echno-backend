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
import java.util.Locale;
import java.util.function.Supplier;

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

    /** Request field names in {@link UserRegistrationDto}, used to tell the form which field is taken. */
    static final String USERNAME_FIELD = "userName";
    static final String EMAIL_FIELD = "email";

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
     * @throws DuplicateResourceException if the username or the email is already taken,
     *     naming the field in either case.
     */
    public String createUser(String username, String email, String password) {
        if (exists(() -> keycloak.realm(realm).users().searchByUsername(username, true), "username", username)) {
            throw usernameTaken(username);
        }
        if (exists(() -> keycloak.realm(realm).users().searchByEmail(email, true), "email", email)) {
            throw emailTaken();
        }

        UsersResource usersResource = keycloak.realm(realm).users();

        UserRepresentation userRepresentation = new UserRepresentation();
        userRepresentation.setUsername(username);
        userRepresentation.setEmail(email);
        userRepresentation.setEnabled(true);
        userRepresentation.setEmailVerified(true);

        String keycloakId;
        try (Response response = usersResource.create(userRepresentation)) {
            if (response.getStatus() == 409) {
                // A user created between the checks above and this call.
                throw duplicateFromConflict(response, username);
            }
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

    static DuplicateResourceException usernameTaken(String username) {
        return new DuplicateResourceException(
                "The username '" + username + "' is already taken. Please choose a different username.",
                USERNAME_FIELD);
    }

    static DuplicateResourceException emailTaken() {
        return new DuplicateResourceException(
                "An account with this email already exists. Please sign in, or register with a different email.",
                EMAIL_FIELD);
    }

    /**
     * Keycloak answers a duplicate with 409 and an {@code errorMessage} such as "User exists
     * with same username" or "... same email". Anything that does not mention the email is
     * reported as a username clash, the case the pre-check cannot fully close.
     */
    private DuplicateResourceException duplicateFromConflict(Response response, String username) {
        String body = "";
        try {
            body = String.valueOf(response.readEntity(String.class));
        } catch (Exception e) {
            logger.debug("Could not read the Keycloak 409 body: {}", e.getMessage());
        }
        return body.toLowerCase(Locale.ROOT).contains("email") ? emailTaken() : usernameTaken(username);
    }

    private boolean exists(Supplier<List<UserRepresentation>> search, String what, String value) {
        try {
            List<UserRepresentation> users = search.get();
            return users != null && !users.isEmpty();
        } catch (Exception ex) {
            throw new DatabaseOperationException("Failed to check whether a user with " + what + " '" + value + "' exists in Keycloak: " + ex.getMessage());
        }
    }
}
