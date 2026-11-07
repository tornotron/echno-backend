package org.tornotron.echno_backend.common.identity.workos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.tornotron.echno_backend.common.identity.EchnoOrgId;
import org.tornotron.echno_backend.common.identity.ExternalInvitationId;
import org.tornotron.echno_backend.common.identity.ExternalUserId;
import org.tornotron.echno_backend.common.identity.IdentityProvider;
import org.tornotron.echno_backend.common.identity.IdentityProviderContract;
import org.tornotron.echno_backend.common.identity.IdentityTokenMapper;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The same contract against a real WorkOS staging environment. Manual: it runs only with
 * {@code WORKOS_SANDBOX_CONTRACT=true} and the environment's {@code WORKOS_API_KEY} and
 * {@code WORKOS_CLIENT_ID} in the environment, never in CI.
 *
 * <p>Every address is {@code proof-...@example.org} and every organization is named {@code proof-...}.
 * A staging environment verifies {@code example.com} for its test SSO connection, so an address there
 * cannot sign in and a verified one joins the seeded organization. Everything a test creates is deleted
 * after it, including the users WorkOS creates when an invitation is sent.
 *
 * <p>What only a person can do here is done through the API: accepting is the accept call after the
 * address is marked verified, which is what following the link does. Sent email cannot be counted and
 * an invitation cannot be made to expire, so those tests are skipped.
 */
@Tag("workos-sandbox")
@EnabledIfEnvironmentVariable(named = "WORKOS_SANDBOX_CONTRACT", matches = "true")
class WorkOsSandboxContractTest extends IdentityProviderContract {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final WorkOsSettings SETTINGS = new WorkOsSettings(
            System.getenv("WORKOS_API_KEY"), System.getenv("WORKOS_CLIENT_ID"), URI.create("https://api.workos.com"));

    private final WorkOsApi api = WorkOsApi.create(SETTINGS,
            new RetryAfterInterceptor(3, Duration.ofSeconds(10), Thread::sleep));
    private final WorkOsIdentityProvider provider = new WorkOsIdentityProvider(api, Clock.systemUTC(), Thread::sleep);
    private final HttpClient http = HttpClient.newBuilder().proxy(ProxySelector.getDefault()).build();
    private final Set<String> emails = ConcurrentHashMap.newKeySet();
    private final Set<EchnoOrgId> organizations = ConcurrentHashMap.newKeySet();
    private final Map<String, String> passwords = new HashMap<>();

    /** Each deletion is tried on its own, so one that fails does not leave the rest behind. */
    @AfterEach
    void deleteEverythingCreated() {
        List<RuntimeException> failures = new ArrayList<>();
        for (String email : emails) {
            try {
                for (WorkOsApi.User user : api.usersByEmail(email)) {
                    call("DELETE", "/user_management/users/" + user.id(), null);
                }
            } catch (RuntimeException e) {
                failures.add(e);
            }
        }
        for (EchnoOrgId organization : organizations) {
            try {
                provider.deleteOrganization(organization);
            } catch (RuntimeException e) {
                failures.add(e);
            }
        }
        if (!failures.isEmpty()) {
            throw failures.get(0);
        }
    }

    @Override
    protected String anEmail() {
        String email = "proof-" + UUID.randomUUID() + "@example.org";
        emails.add(email);
        return email;
    }

    @Override
    protected String organizationName(EchnoOrgId organization) {
        organizations.add(organization);
        return "proof-organization-" + organization.value();
    }

    @Override
    protected IdentityProvider provider() {
        return provider;
    }

    @Override
    protected IdentityTokenMapper tokenMapper() {
        return new WorkOsTokenMapper(SETTINGS);
    }

    @Override
    protected ExternalUserId existingUser(String email, boolean emailVerified, String name) {
        String[] parts = name.split(" ", 2);
        String password = "Pf!" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("email_verified", emailVerified);
        body.put("first_name", parts[0]);
        body.put("password", password);
        if (parts.length > 1) {
            body.put("last_name", parts[1]);
        }
        JsonNode user = call("POST", "/user_management/users", body);
        passwords.put(email, password);
        return new ExternalUserId(user.get("id").asText());
    }

    @Override
    protected boolean isMember(ExternalUserId user, EchnoOrgId organization) {
        return api.organizationByExternalId(WorkOsIdentityProvider.externalId(organization))
                .map(org -> !api.memberships(user.value(), org.id(), "active").isEmpty())
                .orElse(false);
    }

    @Override
    protected ExternalUserId acceptInvitation(ExternalInvitationId invitation, String email) {
        WorkOsApi.User user = api.usersByEmail(email).get(0);
        call("PUT", "/user_management/users/" + user.id(), Map.of("email_verified", true));
        JsonNode accepted = call("POST", "/user_management/invitations/" + invitation.value() + "/accept", null);
        return new ExternalUserId(accepted.get("accepted_user_id").asText());
    }

    @Override
    protected void letExpire(ExternalInvitationId invitation) {
        Assumptions.abort("an invitation cannot be made to expire at WorkOS");
    }

    @Override
    protected int invitationEmailsTo(String email) {
        Assumptions.abort("sent email cannot be counted at WorkOS");
        return 0;
    }

    @Override
    protected int passwordResetEmailsTo(String email) {
        Assumptions.abort("sent email cannot be counted at WorkOS");
        return 0;
    }

    @Override
    protected void startSession(ExternalUserId user, String email) {
        call("POST", "/user_management/authenticate", Map.of(
                "client_id", SETTINGS.clientId(),
                "client_secret", SETTINGS.apiKey(),
                "grant_type", "password",
                "email", email,
                "password", passwords.get(email)));
    }

    @Override
    protected boolean hasSession(ExternalUserId user) {
        return api.sessions(user.value()).stream().anyMatch(session -> "active".equals(session.status()));
    }

    @Override
    protected Jwt.Builder tokenBuilder() {
        return aToken(SETTINGS.defaultIssuer());
    }

    @Override
    protected Map<String, Object> multiFactorClaims() {
        return Map.of();
    }

    @Override
    protected boolean carriesTokenEmail() {
        return false;
    }

    /** The calls only a test makes. Neither the request nor the response is ever printed. */
    private JsonNode call(String method, String path, Object body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://api.workos.com" + path))
                    .header("Authorization", "Bearer " + SETTINGS.apiKey())
                    .header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException(method + " " + path.replaceAll("/[a-z_]+_01[0-9A-Z]+", "/<id>")
                        + " answered " + response.statusCode());
            }
            return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
