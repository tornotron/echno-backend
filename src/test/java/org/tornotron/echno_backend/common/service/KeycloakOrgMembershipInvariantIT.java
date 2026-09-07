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
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.tornotron.echno_backend.common.configuration.JwtAuthConverter;
import org.tornotron.echno_backend.common.enums.OrgRole;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the invariant the whole org-scoped authority model rests on, against a real Keycloak:
 * <b>a user who holds a role subgroup under an organization is a member of that organization</b>.
 *
 * <p>Why it needs pinning. Keycloak records membership of {@code /org-5} and of
 * {@code /org-5/system-admin} as two independent rows, so the two can come apart, and the
 * authorities they mint come apart with them: {@code JwtAuthConverter} derives
 * {@code ORG_MEMBER_5} from the first and {@code ORG_5_ROLE_system-admin} from the second.
 * {@code TenantFilter} resolves the request's organization from the membership authority alone,
 * on both the header branch and the inference branch, so a role held without membership resolves
 * no tenant and its holder is refused on every endpoint, including the ones their role names.
 * That is what made the {@code "member or role"} guards of #709 and #717 decide nothing: the role
 * clause described a state the model has no answer for.
 *
 * <p>The answer taken in #717 is to make the state unreachable rather than to teach
 * {@code TenantFilter} to honour it. Honouring it would mean a role authority alone resolves a
 * tenant, which changes what "the current tenant" means for every request in the application, and
 * the population it would enfranchise is not bootstrap admins: it is users whose membership was
 * removed while their role subgroup stayed behind, which offboarding did until this change.
 *
 * <p>So both directions are proven here, through the real {@link KeycloakGroupService} against a
 * real server rather than a mock of it:
 * <ol>
 *   <li>granting a role also grants membership, even to a user who had none;</li>
 *   <li>removing membership also removes every role subgroup under that organization;</li>
 *   <li>and the group paths that result never yield a role authority without its membership
 *       authority, which is the form the guards actually read.</li>
 * </ol>
 *
 * <p>The third is the one that cannot be faked. A {@code @WebMvcTest} slice mocks
 * {@code @orgSecurity}, so a {@code @PreAuthorize} test there passes on a stub's return value
 * whatever the real beans would decide, which is how the dead clause shipped green in
 * {@code eec6c37}. Running the real Keycloak state through the real converter leaves no stub in
 * the path.
 */
class KeycloakOrgMembershipInvariantIT {

    private static final String TEST_REALM = "echno-group-it-realm";
    private static final String BACKEND_CLIENT_ID = "echno-backend";
    // Placeholder for a container that lives for the length of this class, not a real credential.
    private static final String BACKEND_CLIENT_SECRET = "echno-backend-group-it-secret";
    private static final String REALM_MANAGEMENT = "realm-management";
    private static final String REALM_ADMIN = "realm-admin";
    private static final String ORG_ID = "5";
    private static final String ORG_GROUP_PATH = "/org-" + ORG_ID;

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
        realm.setRealm(TEST_REALM);
        realm.setEnabled(true);
        master.realms().create(realm);

        createBackendServiceAccountClient();

        // The real bean, wired the way Spring wires it. Its @Value fields are set directly
        // because there is no context here; everything below then runs through the same
        // client_credentials path production uses.
        groupService = new KeycloakGroupService();
        ReflectionTestUtils.setField(groupService, "authServerUrl", KEYCLOAK.getAuthServerUrl());
        ReflectionTestUtils.setField(groupService, "realm", TEST_REALM);
        ReflectionTestUtils.setField(groupService, "clientId", BACKEND_CLIENT_ID);
        ReflectionTestUtils.setField(groupService, "clientSecret", BACKEND_CLIENT_SECRET);

        groupService.createOrganizationGroup(ORG_ID, "Org Five");
    }

    @AfterAll
    static void stopKeycloak() {
        if (master != null) {
            master.close();
        }
        KEYCLOAK.stop();
    }

    @BeforeEach
    void createUser() {
        UserRepresentation user = new UserRepresentation();
        user.setUsername("member-" + System.nanoTime());
        user.setEnabled(true);
        try (Response response = master.realm(TEST_REALM).users().create(user)) {
            userId = CreatedResponseUtil.getCreatedId(response);
        }
    }

    @Test
    void assigningARoleToANonMemberAlsoMakesThemAMember() {
        // The provisioning half. Before this, a role could be granted to a user who was in no
        // org group at all, and the grant "succeeded" while leaving them unable to use it.
        groupService.assignOrgRole(userId, ORG_ID, OrgRole.SYSTEM_ADMIN);

        assertThat(groupPaths()).contains(ORG_GROUP_PATH);
    }

    @Test
    void assigningARoleGrantsTheRoleSubgroupToo() {
        groupService.assignOrgRole(userId, ORG_ID, OrgRole.SYSTEM_ADMIN);

        assertThat(groupPaths()).contains(ORG_GROUP_PATH + "/system-admin");
    }

    @Test
    void removingMembershipAlsoRemovesTheRoleSubgroup() {
        // The offboarding half, and the one that made the split reachable in production:
        // leaving the parent group leaves the subgroup row untouched, so a removed employee
        // kept ORG_5_ROLE_system-admin and lost only ORG_MEMBER_5.
        groupService.addUserToOrganization(userId, ORG_ID);
        groupService.assignOrgRole(userId, ORG_ID, OrgRole.SYSTEM_ADMIN);

        groupService.removeUserFromOrganization(userId, ORG_ID);

        assertThat(groupPaths()).doesNotContain(ORG_GROUP_PATH + "/system-admin");
    }

    @Test
    void removingMembershipAlsoRemovesTheParentGroup() {
        groupService.addUserToOrganization(userId, ORG_ID);
        groupService.assignOrgRole(userId, ORG_ID, OrgRole.SYSTEM_ADMIN);

        groupService.removeUserFromOrganization(userId, ORG_ID);

        assertThat(groupPaths()).doesNotContain(ORG_GROUP_PATH);
    }

    @Test
    void removingMembershipClearsEveryRoleHeldInThatOrganization() {
        // More than one role, because the removal walks the user's subgroups rather than one
        // named role, and a loop that stops after the first would still pass the tests above.
        groupService.assignOrgRole(userId, ORG_ID, OrgRole.SYSTEM_ADMIN);
        groupService.assignOrgRole(userId, ORG_ID, OrgRole.HR_ADMIN);

        groupService.removeUserFromOrganization(userId, ORG_ID);

        assertThat(groupPaths()).noneMatch(path -> path.startsWith(ORG_GROUP_PATH));
    }

    @Test
    void aRoleAuthorityIsNeverMintedWithoutItsMembershipAuthority() {
        // The invariant in the form the guards read. The group paths come from the live server
        // after a real role grant, and the converter is the real one, so nothing here can pass
        // on a stubbed answer.
        groupService.assignOrgRole(userId, ORG_ID, OrgRole.PROJECT_MANAGER);

        Set<String> authorities = authoritiesFor(groupPaths());

        assertThat(authorities).contains("ORG_" + ORG_ID + "_ROLE_project-manager");
        assertThat(authorities).contains("ORG_MEMBER_" + ORG_ID);
    }

    @Test
    void anOffboardedUserKeepsNeitherAuthority() {
        groupService.assignOrgRole(userId, ORG_ID, OrgRole.PROJECT_MANAGER);
        groupService.removeUserFromOrganization(userId, ORG_ID);

        Set<String> authorities = authoritiesFor(groupPaths());

        assertThat(authorities).doesNotContain("ORG_" + ORG_ID + "_ROLE_project-manager");
        assertThat(authorities).doesNotContain("ORG_MEMBER_" + ORG_ID);
    }

    /** The paths of every group the user is currently in, read back from the server. */
    private List<String> groupPaths() {
        return master.realm(TEST_REALM).users().get(userId).groups().stream()
                .map(GroupRepresentation::getPath)
                .toList();
    }

    /** The authorities the real converter derives from a token carrying these group paths. */
    private static Set<String> authoritiesFor(List<String> groupPaths) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("user-1")
                .claim("groups", groupPaths)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        JwtAuthConverter converter = new JwtAuthConverter();
        ReflectionTestUtils.setField(converter, "resourceId", "echno-backend-client");

        AbstractAuthenticationToken auth = converter.convert(jwt);
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    /**
     * The confidential client {@link KeycloakGroupService} authenticates as, with a service
     * account holding realm-admin so it can manage groups.
     */
    private static void createBackendServiceAccountClient() {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(BACKEND_CLIENT_ID);
        client.setEnabled(true);
        client.setPublicClient(false);
        client.setServiceAccountsEnabled(true);
        client.setSecret(BACKEND_CLIENT_SECRET);
        client.setStandardFlowEnabled(false);

        String clientUuid;
        try (Response response = master.realm(TEST_REALM).clients().create(client)) {
            clientUuid = CreatedResponseUtil.getCreatedId(response);
        }

        UserRepresentation serviceAccount =
                master.realm(TEST_REALM).clients().get(clientUuid).getServiceAccountUser();
        String realmManagementUuid =
                master.realm(TEST_REALM).clients().findByClientId(REALM_MANAGEMENT).getFirst().getId();
        RoleRepresentation realmAdmin = master.realm(TEST_REALM).clients()
                .get(realmManagementUuid).roles().get(REALM_ADMIN).toRepresentation();

        master.realm(TEST_REALM).users().get(serviceAccount.getId())
                .roles().clientLevel(realmManagementUuid).add(List.of(realmAdmin));
    }
}
