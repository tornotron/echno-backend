package org.tornotron.echno_backend.common.identity.workos;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.lang.Nullable;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The WorkOS endpoints the adapter uses, on Spring's {@link RestClient}, with no SDK.
 *
 * <p>Responses are read into the records below, which name only the fields Echno uses. An
 * invitation's {@code token} and {@code accept_invitation_url}, and a password reset's token and
 * address, are bearer values, so they are never read into anything and never reach a log. Nothing in
 * this class logs a request or a response.
 */
class WorkOsApi {

    record Organization(String id, String name, @Nullable String externalId) {
    }

    record User(String id, String email, boolean emailVerified, @Nullable String firstName,
                @Nullable String lastName) {
    }

    record Membership(String id, String userId, String organizationId, String status) {
    }

    record Invitation(String id, String email, String state, String organizationId,
                      @Nullable String acceptedUserId, @Nullable Instant expiresAt) {
    }

    record Session(String id, String status) {
    }

    record Page<T>(List<T> data, @Nullable ListMetadata listMetadata) {
    }

    record ListMetadata(@Nullable String after) {
    }

    record ApiError(@Nullable String code, @Nullable String message, @Nullable List<ApiError> errors) {
    }

    private static final int PAGE_SIZE = 100;

    private final RestClient http;
    private final ObjectMapper json;

    WorkOsApi(RestClient http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    static WorkOsApi create(WorkOsSettings settings, RetryAfterInterceptor rateLimit) {
        ObjectMapper json = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .addModule(new JavaTimeModule())
                .build();
        // The JVM's proxy settings, if any (https.proxyHost), for a stack whose egress goes through one.
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .proxy(ProxySelector.getDefault())
                .build());
        requests.setReadTimeout(Duration.ofSeconds(20));
        RestClient http = RestClient.builder()
                .baseUrl(settings.apiUrl().toString())
                .requestFactory(requests)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + settings.apiKey())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .messageConverters(converters -> {
                    converters.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
                    converters.add(new MappingJackson2HttpMessageConverter(json));
                })
                .requestInterceptor(rateLimit)
                .build();
        return new WorkOsApi(http, json);
    }

    // --- Organizations ---------------------------------------------------------------------------

    Organization createOrganization(String name, String externalId) {
        return post("/organizations", Map.of("name", name, "external_id", externalId), Organization.class);
    }

    Optional<Organization> organizationByExternalId(String externalId) {
        return found(() -> get(uri -> uri.path("/organizations/external_id/{id}").build(externalId),
                Organization.class));
    }

    void deleteOrganization(String id) {
        delete(uri -> uri.path("/organizations/{id}").build(id));
    }

    /** The cheapest authenticated read, for the health check. */
    void listOneOrganization() {
        get(uri -> uri.path("/organizations").queryParam("limit", 1).build(), Map.class);
    }

    // --- Users and sessions ----------------------------------------------------------------------

    Optional<User> user(String id) {
        return found(() -> get(uri -> uri.path("/user_management/users/{id}").build(id), User.class));
    }

    List<User> usersByEmail(String email) {
        // A template variable, so a '+' in the address is encoded and not read as a space.
        return all(after -> get(uri -> page(uri.path("/user_management/users").queryParam("email", "{email}"), after)
                        .build(email),
                new ParameterizedTypeReference<Page<User>>() { }));
    }

    List<Session> sessions(String userId) {
        return all(after -> get(uri -> page(uri.path("/user_management/users/{id}/sessions"), after).build(userId),
                new ParameterizedTypeReference<Page<Session>>() { }));
    }

    void revokeSession(String sessionId) {
        post("/user_management/sessions/revoke", Map.of("session_id", sessionId), Void.class);
    }

    /** WorkOS sends the email. The response holds the reset token, so it is not read. */
    void sendPasswordReset(String email) {
        post("/user_management/password_reset", Map.of("email", email), Void.class);
    }

    // --- Memberships -----------------------------------------------------------------------------

    Membership createMembership(String userId, String organizationId) {
        return post("/user_management/organization_memberships",
                Map.of("user_id", userId, "organization_id", organizationId), Membership.class);
    }

    /** Every membership of the person in the organization with one of these statuses. */
    List<Membership> memberships(String userId, String organizationId, String... statuses) {
        return all(after -> get(uri -> {
            uri.path("/user_management/organization_memberships")
                    .queryParam("user_id", userId)
                    .queryParam("organization_id", organizationId);
            for (String status : statuses) {
                uri.queryParam("statuses", status);
            }
            return page(uri, after).build();
        }, new ParameterizedTypeReference<Page<Membership>>() { }));
    }

    void deleteMembership(String id) {
        delete(uri -> uri.path("/user_management/organization_memberships/{id}").build(id));
    }

    // --- Invitations -----------------------------------------------------------------------------

    Invitation invite(String email, String organizationId, @Nullable String inviterUserId, int expiresInDays) {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("organization_id", organizationId);
        body.put("expires_in_days", expiresInDays);
        if (inviterUserId != null) {
            body.put("inviter_user_id", inviterUserId);
        }
        return post("/user_management/invitations", body, Invitation.class);
    }

    Optional<Invitation> invitation(String id) {
        return found(() -> get(uri -> uri.path("/user_management/invitations/{id}").build(id), Invitation.class));
    }

    void resendInvitation(String id) {
        post(uri -> uri.path("/user_management/invitations/{id}/resend").build(id));
    }

    void revokeInvitation(String id) {
        post(uri -> uri.path("/user_management/invitations/{id}/revoke").build(id));
    }

    // --- Plumbing --------------------------------------------------------------------------------

    private <T> T get(Function<UriBuilder, URI> uri, Class<T> type) {
        return http.get().uri(uri).retrieve().onStatus(status -> status.isError(), this::refuse).body(type);
    }

    private <T> T get(Function<UriBuilder, URI> uri, ParameterizedTypeReference<T> type) {
        return http.get().uri(uri).retrieve().onStatus(status -> status.isError(), this::refuse).body(type);
    }

    private <T> T post(String path, Object body, Class<T> type) {
        return http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().onStatus(status -> status.isError(), this::refuse).body(type);
    }

    private void post(Function<UriBuilder, URI> uri) {
        http.post().uri(uri).retrieve().onStatus(status -> status.isError(), this::refuse).toBodilessEntity();
    }

    private void delete(Function<UriBuilder, URI> uri) {
        http.delete().uri(uri).retrieve().onStatus(status -> status.isError(), this::refuse).toBodilessEntity();
    }

    private static UriBuilder page(UriBuilder uri, @Nullable String after) {
        uri.queryParam("limit", PAGE_SIZE);
        if (after != null) {
            uri.queryParam("after", after);
        }
        return uri;
    }

    private static <T> List<T> all(Function<String, Page<T>> fetch) {
        List<T> everything = new ArrayList<>();
        String after = null;
        do {
            Page<T> page = fetch.apply(after);
            if (page == null) {
                break;
            }
            everything.addAll(page.data());
            after = page.listMetadata() == null ? null : page.listMetadata().after();
        } while (after != null);
        return everything;
    }

    private static <T> Optional<T> found(Supplier<T> call) {
        try {
            return Optional.ofNullable(call.get());
        } catch (WorkOsApiException refused) {
            if (refused.notFound()) {
                return Optional.empty();
            }
            throw refused;
        }
    }

    /** Reads WorkOS's error code and message, preferring a more specific nested one. */
    private void refuse(HttpRequest request, ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        ApiError error = null;
        try (InputStream body = response.getBody()) {
            error = json.readValue(body, ApiError.class);
        } catch (IOException | RuntimeException unreadable) {
            // The status alone has to do.
        }
        String code = error == null ? null : error.code();
        String message = error == null ? null : error.message();
        if (error != null && error.errors() != null && !error.errors().isEmpty() && error.errors().get(0).code() != null) {
            code = error.errors().get(0).code();
            message = error.errors().get(0).message();
        }
        throw new WorkOsApiException(status, code, message);
    }
}
