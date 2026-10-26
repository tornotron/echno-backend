package org.tornotron.echno_backend.user;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A taken username or email on registration must come back as a 409 that names the field,
 * whether the pre-check catches it or Keycloak does. Before, a username clash reached Keycloak,
 * whose 409 was rethrown as a bare RuntimeException and left as a 500.
 *
 * <p>Plain Mockito against the admin client, no Keycloak container.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KeycloakUserProvisioningServiceTest {

    @Mock private Keycloak keycloak;
    @Mock private RealmResource realmResource;
    @Mock private UsersResource usersResource;

    private KeycloakUserProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new KeycloakUserProvisioningService(keycloak);
        ReflectionTestUtils.setField(service, "realm", "echno-realm");
        when(keycloak.realm("echno-realm")).thenReturn(realmResource);
        when(realmResource.users()).thenReturn(usersResource);
        when(usersResource.searchByUsername(any(), any())).thenReturn(List.of());
        when(usersResource.searchByEmail(any(), any())).thenReturn(List.of());
        when(usersResource.search(any(String.class), any(Boolean.class))).thenReturn(List.of());
    }

    private static Response conflict(String body) {
        Response response = mock(Response.class);
        when(response.getStatus()).thenReturn(409);
        when(response.readEntity(String.class)).thenReturn(body);
        return response;
    }

    @Test
    @DisplayName("a username Keycloak already holds is refused before create, naming userName")
    void takenUsername_isRefusedBeforeCreate() {
        when(usersResource.searchByUsername("Anand", true)).thenReturn(List.of(new UserRepresentation()));

        DuplicateResourceException ex = catchThrowableOfType(
                () -> service.createUser("Anand", "new@example.com", "Secret#123"),
                DuplicateResourceException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getField()).isEqualTo("userName");
        assertThat(ex.getMessage()).contains("username 'Anand' is already taken");
        verify(usersResource, never()).create(any());
    }

    @Test
    @DisplayName("an email Keycloak already holds is refused before create, naming email")
    void takenEmail_isRefusedBeforeCreate() {
        when(usersResource.searchByEmail("taken@example.com", true)).thenReturn(List.of(new UserRepresentation()));

        DuplicateResourceException ex = catchThrowableOfType(
                () -> service.createUser("fresh", "taken@example.com", "Secret#123"),
                DuplicateResourceException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getField()).isEqualTo("email");
        verify(usersResource, never()).create(any());
    }

    @Test
    @DisplayName("a Keycloak 409 on username becomes a duplicate naming userName, not a 500")
    void keycloak409OnUsername_isDuplicateUsername() {
        Response response = conflict("{\"errorMessage\":\"User exists with same username\"}");
        when(usersResource.create(any())).thenReturn(response);

        DuplicateResourceException ex = catchThrowableOfType(
                () -> service.createUser("Anand", "new@example.com", "Secret#123"),
                DuplicateResourceException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getField()).isEqualTo("userName");
        assertThat(ex.getMessage()).doesNotContain("Keycloak").doesNotContain("409");
    }

    @Test
    @DisplayName("a Keycloak 409 on email becomes a duplicate naming email")
    void keycloak409OnEmail_isDuplicateEmail() {
        Response response = conflict("{\"errorMessage\":\"User exists with same email\"}");
        when(usersResource.create(any())).thenReturn(response);

        DuplicateResourceException ex = catchThrowableOfType(
                () -> service.createUser("fresh", "taken@example.com", "Secret#123"),
                DuplicateResourceException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getField()).isEqualTo("email");
    }
}
