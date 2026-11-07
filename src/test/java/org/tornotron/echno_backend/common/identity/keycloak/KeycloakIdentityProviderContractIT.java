package org.tornotron.echno_backend.common.identity.keycloak;

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
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.identity.EchnoOrgId;
import org.tornotron.echno_backend.common.identity.ExternalInvitationId;
import org.tornotron.echno_backend.common.identity.ExternalUserId;
import org.tornotron.echno_backend.common.identity.IdentityProvider;
import org.tornotron.echno_backend.common.identity.IdentityProviderContract;
import org.tornotron.echno_backend.common.identity.IdentityTokenMapper;
import org.tornotron.echno_backend.common.identity.InvitationState;
import org.tornotron.echno_backend.common.identity.InvitationStatus;
import org.tornotron.echno_backend.common.identity.OrgRoleGroups;
import org.tornotron.echno_backend.common.identity.SelfRegistration;
import org.tornotron.echno_backend.common.identity.SignInMethod;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The port's contract against a real Keycloak and a real mail catcher.
 *
 * <p>The backend's service account holds only the roles the realm bootstrap grants it, not realm-admin,
 * so a call that needs more than a deployment has fails here rather than on a staging.
 *
 * <p>Accepting an invitation is the one thing not run for real: it is a browser form behind the link in
 * the email. {@link #acceptInvitation} does to the account what finishing that form does.
 */
class KeycloakIdentityProviderContractIT extends IdentityProviderContract {

    private static final String REALM = "echno-contract-it-realm";
    private static final String BACKEND_CLIENT_ID = "echno-backend";
    // Placeholders for containers that live for the length of this class, not real credentials.
    private static final String BACKEND_CLIENT_SECRET = "echno-backend-contract-it-secret";
    private static final String USER_PASSWORD = "contract-it-password";
    private static final String LOGIN_CLIENT_ID = "contract-it-login";
    /** What the realm bootstrap grants the backend's service account. */
    private static final List<String> SERVICE_ACCOUNT_ROLES =
            List.of("manage-users", "view-users", "manage-groups", "query-groups", "query-users");

    private static final Network NETWORK = Network.newNetwork();
    private static final String MAIL_HOST = "mail";
    private static final int MAIL_SMTP_PORT = 1025;
    private static final int MAIL_API_PORT = 8025;

    private static final GenericContainer<?> MAIL =
            new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.21.8"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases(MAIL_HOST)
                    .withExposedPorts(MAIL_SMTP_PORT, MAIL_API_PORT);

    // Pinned to the tag matching the keycloak-admin-client version, as the other Keycloak ITs are.
    private static final KeycloakContainer KEYCLOAK =
            new KeycloakContainer("quay.io/keycloak/keycloak:26.0.7").withNetwork(NETWORK);

    private static Keycloak master;
    private static KeycloakIdentityProvider provider;
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void startKeycloak() {
        MAIL.start();
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
        realm.setSmtpServer(Map.of(
                "host", MAIL_HOST,
                "port", Integer.toString(MAIL_SMTP_PORT),
                "from", "no-reply@example.test"));
        master.realms().create(realm);

        createBackendServiceAccount();
        createLoginClient();

        KeycloakGroupService groups = new KeycloakGroupService();
        ReflectionTestUtils.setField(groups, "authServerUrl", KEYCLOAK.getAuthServerUrl());
        ReflectionTestUtils.setField(groups, "realm", REALM);
        ReflectionTestUtils.setField(groups, "clientId", BACKEND_CLIENT_ID);
        ReflectionTestUtils.setField(groups, "clientSecret", BACKEND_CLIENT_SECRET);

        KeycloakUserProvisioningService provisioning = new KeycloakUserProvisioningService(master);
        ReflectionTestUtils.setField(provisioning, "realm", REALM);

        provider = new KeycloakIdentityProvider(groups, provisioning, new KeycloakInvitations(groups));
    }

    @AfterAll
    static void stopKeycloak() {
        if (master != null) {
            master.close();
        }
        KEYCLOAK.stop();
        MAIL.stop();
    }

    @Override
    protected IdentityProvider provider() {
        return provider;
    }

    @Override
    protected IdentityTokenMapper tokenMapper() {
        return new KeycloakTokenMapper();
    }

    @Override
    protected ExternalUserId existingUser(String email, boolean emailVerified, String name) {
        String[] names = name.split(" ", 2);
        UserRepresentation user = new UserRepresentation();
        user.setUsername(email);
        user.setEmail(email);
        user.setEnabled(true);
        user.setEmailVerified(emailVerified);
        user.setFirstName(names[0]);
        user.setLastName(names.length > 1 ? names[1] : null);
        try (Response response = master.realm(REALM).users().create(user)) {
            return new ExternalUserId(CreatedResponseUtil.getCreatedId(response));
        }
    }

    @Override
    protected boolean isMember(ExternalUserId user, EchnoOrgId organization) {
        return groupPaths(user).contains("/org-" + organization.value());
    }

    @Override
    protected ExternalUserId acceptInvitation(ExternalInvitationId invitation, String email) {
        UserRepresentation account = master.realm(REALM).users().searchByEmail(email, true).getFirst();
        account.setRequiredActions(List.of());
        account.setEmailVerified(true);
        master.realm(REALM).users().get(account.getId()).update(account);
        return new ExternalUserId(account.getId());
    }

    @Override
    protected void letExpire(ExternalInvitationId invitation) {
        GroupRepresentation record = master.realm(REALM).groups().group(invitation.value()).toRepresentation();
        record.singleAttribute(KeycloakInvitations.EXPIRES_AT, Instant.now().minusSeconds(60).toString());
        master.realm(REALM).groups().group(invitation.value()).update(record);
    }

    @Override
    protected int invitationEmailsTo(String email) {
        return mailsTo(email);
    }

    @Override
    protected int passwordResetEmailsTo(String email) {
        return mailsTo(email);
    }

    @Override
    protected void startSession(ExternalUserId user, String email) {
        CredentialRepresentation password = new CredentialRepresentation();
        password.setType(CredentialRepresentation.PASSWORD);
        password.setValue(USER_PASSWORD);
        password.setTemporary(false);
        master.realm(REALM).users().get(user.value()).resetPassword(password);

        String form = "grant_type=password&client_id=" + LOGIN_CLIENT_ID
                + "&username=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                + "&password=" + USER_PASSWORD;
        try {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                                    URI.create(KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM + "/protocol/openid-connect/token"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Could not sign in", e);
        }
    }

    @Override
    protected boolean hasSession(ExternalUserId user) {
        return !master.realm(REALM).users().get(user.value()).getUserSessions().isEmpty();
    }

    @Override
    protected Jwt.Builder tokenBuilder() {
        return aToken(KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM);
    }

    @Override
    protected Map<String, Object> multiFactorClaims() {
        return Map.of("amr", List.of("pwd", "otp"));
    }

    @Test
    void aPasswordSignInIsRecognised() {
        Jwt token = tokenBuilder().subject("s").claim("amr", List.of("pwd")).build();

        assertThat(tokenMapper().toPrincipal(token).authentication())
                .isEqualTo(SignInMethod.PASSWORD);
    }

    @Test
    void anUnreadableAmrCountsAsTheWeakest() {
        Jwt token = tokenBuilder().subject("s").claim("amr", "pwd").build();

        assertThat(tokenMapper().toPrincipal(token).authentication())
                .isEqualTo(SignInMethod.UNKNOWN);
    }

    @Test
    void anInvitationCreatesAnAccountThatCannotSignInUntilAccepted() {
        String email = "invited-" + UUID.randomUUID() + "@example.test";
        EchnoOrgId organization = new EchnoOrgId(900_000_000L + System.nanoTime() % 1_000_000);
        provider.createOrganization(organization, "Invitations");

        provider.invite(email, organization, null, Duration.ofDays(1));

        UserRepresentation account = master.realm(REALM).users().searchByEmail(email, true).getFirst();
        assertThat(account.getRequiredActions()).contains("UPDATE_PASSWORD");
        assertThat(account.isEmailVerified()).isFalse();
        assertThat(master.realm(REALM).users().get(account.getId()).credentials())
                .noneMatch(credential -> CredentialRepresentation.PASSWORD.equals(credential.getType()));
    }

    @Test
    void revokingDeletesTheAccountItCreatedButNotOneThatWasThereBefore() {
        EchnoOrgId organization = new EchnoOrgId(900_000_000L + System.nanoTime() % 1_000_000);
        provider.createOrganization(organization, "Revocations");
        String newPerson = "new-" + UUID.randomUUID() + "@example.test";
        String existingPerson = "existing-" + UUID.randomUUID() + "@example.test";
        existingUser(existingPerson, true, "A Person");

        provider.revokeInvitation(provider.invite(newPerson, organization, null, Duration.ofDays(1)));
        provider.revokeInvitation(provider.invite(existingPerson, organization, null, Duration.ofDays(1)));

        assertThat(master.realm(REALM).users().searchByEmail(newPerson, true)).isEmpty();
        assertThat(master.realm(REALM).users().searchByEmail(existingPerson, true)).hasSize(1);
    }

    @Test
    void aGroupThatIsNotAnInvitationHasNoState() {
        EchnoOrgId organization = new EchnoOrgId(900_000_000L + System.nanoTime() % 1_000_000);
        String organizationGroup = provider.createOrganization(organization, "Not an invitation").value();

        assertThat(provider.invitationState(new ExternalInvitationId(organizationGroup))).isEmpty();
    }

    @Test
    void aFailedEmailLeavesNothingBehind() {
        String otherRealm = "echno-no-smtp-realm";
        RealmRepresentation realm = new RealmRepresentation();
        realm.setRealm(otherRealm);
        realm.setEnabled(true);
        master.realms().create(realm);
        // Same admin rights in a realm with no SMTP settings, using the master client for simplicity.
        KeycloakGroupService groups = new KeycloakGroupService() {
            @Override
            Keycloak adminClient() {
                return KeycloakBuilder.builder()
                        .serverUrl(KEYCLOAK.getAuthServerUrl())
                        .realm("master")
                        .clientId("admin-cli")
                        .username(KEYCLOAK.getAdminUsername())
                        .password(KEYCLOAK.getAdminPassword())
                        .grantType(OAuth2Constants.PASSWORD)
                        .build();
            }
        };
        ReflectionTestUtils.setField(groups, "realm", otherRealm);
        GroupRepresentation organizationGroup = new GroupRepresentation();
        organizationGroup.setName("org-7");
        master.realm(otherRealm).groups().add(organizationGroup).close();
        String email = "no-smtp-" + UUID.randomUUID() + "@example.test";

        assertThatThrownBy(() -> new KeycloakInvitations(groups)
                .invite(email, new EchnoOrgId(7L), null, Duration.ofDays(1)))
                .hasMessageContaining("SMTP");

        assertThat(master.realm(otherRealm).users().searchByEmail(email, true)).isEmpty();
        assertThat(master.realm(otherRealm).groups().groups(KeycloakInvitations.PARENT_GROUP, true, 0, 1, false))
                .allSatisfy(parent -> assertThat(parent.getSubGroupCount()).isZero());
    }

    @Test
    void selfRegistrationIsOffered() {
        SelfRegistration registration = provider.capabilities().selfRegistration().orElseThrow();
        String email = "registered-" + UUID.randomUUID() + "@example.test";

        ExternalUserId account = registration.createAccount(email, email, USER_PASSWORD);

        assertThat(provider.profile(account)).isPresent();
        assertThatThrownBy(() -> registration.createAccount(email, email, USER_PASSWORD))
                .isInstanceOf(DuplicateResourceException.class);

        registration.deleteAccount(account);

        assertThat(provider.profile(account)).isEmpty();
    }

    @Test
    void roleSubgroupsFollowTheExtra() {
        OrgRoleGroups roleGroups = provider.capabilities().orgRoleGroups().orElseThrow();
        EchnoOrgId organization = new EchnoOrgId(900_000_000L + System.nanoTime() % 1_000_000);
        provider.createOrganization(organization, "Roles");
        ExternalUserId user = existingUser("roles-" + UUID.randomUUID() + "@example.test", true, "A Person");
        String path = "/org-" + organization.value();

        roleGroups.assign(user, organization, OrgRole.SYSTEM_ADMIN);
        assertThat(groupPaths(user)).contains(path, path + "/system-admin");

        roleGroups.remove(user, organization, OrgRole.SYSTEM_ADMIN);
        assertThat(groupPaths(user)).contains(path).doesNotContain(path + "/system-admin");
    }

    @Test
    void removingAMemberAlsoRemovesTheirRoleSubgroups() {
        OrgRoleGroups roleGroups = provider.capabilities().orgRoleGroups().orElseThrow();
        EchnoOrgId organization = new EchnoOrgId(900_000_000L + System.nanoTime() % 1_000_000);
        provider.createOrganization(organization, "Offboarding");
        ExternalUserId user = existingUser("leaver-" + UUID.randomUUID() + "@example.test", true, "A Person");
        roleGroups.assign(user, organization, OrgRole.PROJECT_MANAGER);

        provider.removeMember(user, organization);

        assertThat(groupPaths(user)).noneMatch(group -> group.startsWith("/org-" + organization.value()));
    }

    @Test
    void anInvitationThatHasRunOutStillSaysSoWhenTheAccountWasThereBefore() {
        EchnoOrgId organization = new EchnoOrgId(900_000_000L + System.nanoTime() % 1_000_000);
        provider.createOrganization(organization, "Expiry");
        String email = "expiring-" + UUID.randomUUID() + "@example.test";
        existingUser(email, true, "A Person");

        ExternalInvitationId invitation = provider.invite(email, organization, null, Duration.ofDays(1));
        letExpire(invitation);

        assertThat(provider.invitationState(invitation))
                .contains(InvitationStatus.of(InvitationState.EXPIRED));
    }

    private List<String> groupPaths(ExternalUserId user) {
        return master.realm(REALM).users().get(user.value()).groups().stream()
                .map(GroupRepresentation::getPath)
                .toList();
    }

    private static int mailsTo(String email) {
        try {
            String query = URLEncoder.encode("to:\"" + email + "\"", StandardCharsets.UTF_8);
            URI uri = URI.create("http://" + MAIL.getHost() + ":" + MAIL.getMappedPort(MAIL_API_PORT)
                    + "/api/v1/search?query=" + query);
            HttpResponse<String> response =
                    HTTP.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
            return JSON.readTree(response.body()).path("messages_count").asInt();
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the mail catcher", e);
        }
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
        // Keycloak has no manage-groups role, and the bootstrap skips any name it cannot find. So does this.
        List<RoleRepresentation> roles = master.realm(REALM).clients().get(realmManagement).roles().list().stream()
                .filter(role -> SERVICE_ACCOUNT_ROLES.contains(role.getName()))
                .toList();
        master.realm(REALM).users().get(serviceAccount.getId()).roles().clientLevel(realmManagement).add(roles);
    }

    private static void createLoginClient() {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(LOGIN_CLIENT_ID);
        client.setEnabled(true);
        client.setPublicClient(true);
        client.setDirectAccessGrantsEnabled(true);
        master.realm(REALM).clients().create(client).close();
    }
}
