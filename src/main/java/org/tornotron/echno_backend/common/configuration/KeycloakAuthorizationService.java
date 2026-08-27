package org.tornotron.echno_backend.common.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.Set;


@Service
@Slf4j
public class KeycloakAuthorizationService {

    /** OAuth error codes that mean the caller's own access token was rejected. */
    private static final Set<String> TOKEN_ERRORS = Set.of("invalid_token", "invalid_grant");

    /** Keycloak's code for "the token is fine, but it grants no permission on this resource". */
    private static final String ACCESS_DENIED = "access_denied";

    /** Upper bound on how much of Keycloak's response body reaches the log, so one bad token cannot flood it. */
    private static final int MAX_LOGGED_BODY = 512;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Value("${keycloak.issuer-uri}")
    private String issuerUri;

    @Value("${jwt.auth.converter.resource-id}")
    private String clientId;

    // Bounded timeouts so a slow or hung Keycloak cannot pin Tomcat worker threads
    // indefinitely (this exchange runs on every authenticated request).
    private final RestTemplate restTemplate;

    public KeycloakAuthorizationService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restTemplate = new RestTemplate(factory);
    }

    /** Test seam: builds the service around a supplied client and configuration, bypassing Spring. */
    KeycloakAuthorizationService(RestTemplate restTemplate, String issuerUri, String clientId) {
        this.restTemplate = restTemplate;
        this.issuerUri = issuerUri;
        this.clientId = clientId;
    }

    /**
     * Exchanges the caller's access token for a Requesting Party Token via Keycloak's UMA ticket grant.
     *
     * <p>Every failure is classified before it leaves this method, because the same generic error used
     * to cover a stale caller token, an unreachable Keycloak and a bad client registration alike. See
     * {@link RPTExchangeException.Reason} for the categories; {@link RPTExchangeFilter} turns them into
     * the response the caller sees.
     *
     * @param accessToken the caller's bearer access token (raw compact JWT, no "Bearer " prefix)
     * @return the minted RPT
     * @throws RPTExchangeException with a classified {@link RPTExchangeException.Reason} on any failure
     */
    public String exchangeForRPT(String accessToken) {
        String tokenEndpoint = issuerUri + "/protocol/openid-connect/token";

        log.debug("Attempting to exchange access token for RPT at endpoint: {}", tokenEndpoint);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBearerAuth(accessToken);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "urn:ietf:params:oauth:grant-type:uma-ticket");
        params.add("audience", clientId);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(tokenEndpoint, request, Map.class);
            Map<?, ?> body = response.getBody();
            Object rptToken = body == null ? null : body.get("access_token");

            if (rptToken instanceof String rpt && !rpt.isBlank()) {
                log.debug("Successfully exchanged access token for RPT");
                return rpt;
            }

            int status = response.getStatusCode().value();
            log.error("RPT exchange at {} returned HTTP {} without an access_token field. Keys present: {}",
                    tokenEndpoint, status, body == null ? "<no body>" : body.keySet());
            throw new RPTExchangeException(RPTExchangeException.Reason.PROTOCOL_VIOLATION, status, null,
                    "Keycloak returned a successful response with no access_token");

        } catch (HttpClientErrorException e) {
            throw classifyClientError(tokenEndpoint, e);

        } catch (HttpServerErrorException e) {
            String responseBody = truncate(e.getResponseBodyAsString());
            log.error("RPT exchange at {} failed: Keycloak returned HTTP {}. Body: {}",
                    tokenEndpoint, e.getStatusCode().value(), responseBody);
            throw new RPTExchangeException(RPTExchangeException.Reason.KEYCLOAK_UNAVAILABLE,
                    e.getStatusCode().value(), responseBody, "Keycloak returned a server error", e);

        } catch (ResourceAccessException e) {
            log.error("RPT exchange at {} failed: Keycloak is unreachable ({})", tokenEndpoint, e.getMessage());
            throw new RPTExchangeException(RPTExchangeException.Reason.KEYCLOAK_UNAVAILABLE, null, null,
                    "Keycloak could not be reached", e);

        } catch (RestClientException e) {
            // Anything left over from the client: an unreadable payload, a bad redirect, an unknown content type.
            log.error("RPT exchange at {} failed with an unexpected client error: {}", tokenEndpoint, e.getMessage());
            throw new RPTExchangeException(RPTExchangeException.Reason.PROTOCOL_VIOLATION, null, null,
                    "Unexpected failure talking to Keycloak", e);
        }
    }

    /**
     * Splits a 4xx from Keycloak into the caller's problem and ours.
     *
     * <p>The OAuth error code in the body is checked first and the HTTP status is only a fallback,
     * because the two disagree in a way that matters. Keycloak answers 401 with {@code invalid_client}
     * when <em>our</em> client credentials are wrong, so trusting the status would report a bad client
     * secret as the caller's expired token: logged below ERROR where nobody looks, and handed back as
     * {@code invalid_token}, which invites the client to re-authenticate in a loop against a server
     * whose configuration is broken. An unambiguous error code always beats a guess from the status.
     *
     * <p>So: {@code invalid_token} or {@code invalid_grant} means the caller's token was rejected,
     * {@code access_denied} means the token was fine but carries no permission, and any other explicit
     * code (unknown client, unknown audience, bad client credentials, authorization services switched
     * off) is a misconfiguration on this side. Only when the body carries no readable code does the
     * status decide, and there a 401 is the caller's token.
     */
    private RPTExchangeException classifyClientError(String tokenEndpoint, HttpClientErrorException e) {
        int status = e.getStatusCode().value();
        // Classify on the whole body and truncate only what is logged and carried: truncating first
        // can cut a long payload mid-JSON, so the error code would be unreadable and misclassified.
        String fullBody = e.getResponseBodyAsString();
        String oauthError = oauthErrorCode(fullBody);
        String responseBody = truncate(fullBody);

        boolean tokenRejected = oauthError == null ? status == 401 : TOKEN_ERRORS.contains(oauthError);
        if (tokenRejected) {
            // Routine: a caller turned up with an expired or revoked token. Logged below ERROR on purpose,
            // one stale token can otherwise produce hundreds of lines a minute.
            log.warn("RPT exchange at {} rejected the caller's access token: HTTP {}, error={}. Body: {}",
                    tokenEndpoint, status, oauthError, responseBody);
            return new RPTExchangeException(RPTExchangeException.Reason.TOKEN_INVALID, status, responseBody,
                    "Keycloak rejected the access token", e);
        }

        if (ACCESS_DENIED.equals(oauthError)) {
            log.warn("RPT exchange at {} denied permission for the caller: HTTP {}. Body: {}",
                    tokenEndpoint, status, responseBody);
            return new RPTExchangeException(RPTExchangeException.Reason.PERMISSION_DENIED, status, responseBody,
                    "Keycloak granted no permissions for the access token", e);
        }

        log.error("RPT exchange at {} is misconfigured: Keycloak returned HTTP {}, error={}. Body: {}",
                tokenEndpoint, status, oauthError, responseBody);
        return new RPTExchangeException(RPTExchangeException.Reason.EXCHANGE_MISCONFIGURED, status, responseBody,
                "Keycloak rejected the RPT exchange configuration", e);
    }

    /**
     * Reads the OAuth {@code error} code out of an error payload, or returns {@code null} if there is
     * none. Must be given the untruncated body, or a long payload parses as malformed JSON and the
     * code is lost.
     */
    @Nullable
    private static String oauthErrorCode(@Nullable String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            JsonNode error = OBJECT_MAPPER.readTree(responseBody).get("error");
            return error == null || !error.isTextual() ? null : error.asText();
        } catch (Exception ex) {
            // Not a JSON error payload (an HTML error page from a proxy, say). Nothing to classify on.
            return null;
        }
    }

    /** Bounds a response body for logging. Keycloak error payloads carry no bearer material. */
    @Nullable
    private static String truncate(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        return body.length() <= MAX_LOGGED_BODY ? body : body.substring(0, MAX_LOGGED_BODY) + "...(truncated)";
    }
}
