package org.tornotron.echno_backend.common.configuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the failure classification in {@link KeycloakAuthorizationService}. A mocked
 * {@link RestTemplate} stands in for Keycloak's token endpoint, so each branch can be driven directly:
 * no Spring context and no network.
 */
class KeycloakAuthorizationServiceTest {

    private static final String ACCESS_TOKEN = "header.payload.signature";

    private RestTemplate restTemplate;
    private KeycloakAuthorizationService service;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        service = new KeycloakAuthorizationService(restTemplate, "https://auth.example.test/realms/echno-realm", "echno-api");
    }

    @SuppressWarnings("rawtypes")
    private void keycloakAnswers(Map body) {
        ResponseEntity<Map> response = new ResponseEntity<>(body, HttpStatus.OK);
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class))).thenReturn(response);
    }

    private void keycloakThrows(RuntimeException e) {
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class))).thenThrow(e);
    }

    private static HttpClientErrorException clientError(HttpStatus status, String body) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), HttpHeaders.EMPTY,
                body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private RPTExchangeException exchangeFailure() {
        return assertThrows(RPTExchangeException.class, () -> service.exchangeForRPT(ACCESS_TOKEN));
    }

    @Test
    void successfulExchange_returnsTheRpt() {
        keycloakAnswers(Map.of("access_token", "rpt-value"));

        assertEquals("rpt-value", service.exchangeForRPT(ACCESS_TOKEN));
    }

    @Test
    void unauthorizedFromKeycloak_classifiesAsTokenInvalid() {
        // The live failure behind issue #458: the BFF forwarded an expired access token.
        keycloakThrows(clientError(HttpStatus.UNAUTHORIZED, ""));

        RPTExchangeException e = exchangeFailure();

        assertEquals(RPTExchangeException.Reason.TOKEN_INVALID, e.getReason());
        assertEquals(401, e.getStatus().intValue());
    }

    @Test
    void invalidTokenErrorBody_classifiesAsTokenInvalid() {
        keycloakThrows(clientError(HttpStatus.BAD_REQUEST, "{\"error\":\"invalid_token\"}"));

        RPTExchangeException e = exchangeFailure();

        assertEquals(RPTExchangeException.Reason.TOKEN_INVALID, e.getReason());
        assertEquals(400, e.getStatus().intValue());
        assertTrue(e.getResponseBody().contains("invalid_token"));
    }

    @Test
    void invalidGrantErrorBody_classifiesAsTokenInvalid() {
        keycloakThrows(clientError(HttpStatus.BAD_REQUEST, "{\"error\":\"invalid_grant\",\"error_description\":\"Token is not active\"}"));

        assertEquals(RPTExchangeException.Reason.TOKEN_INVALID, exchangeFailure().getReason());
    }

    @Test
    void accessDenied_classifiesAsPermissionDenied() {
        keycloakThrows(clientError(HttpStatus.FORBIDDEN, "{\"error\":\"access_denied\"}"));

        assertEquals(RPTExchangeException.Reason.PERMISSION_DENIED, exchangeFailure().getReason());
    }

    @Test
    void invalidClient_classifiesAsMisconfiguration() {
        keycloakThrows(clientError(HttpStatus.BAD_REQUEST, "{\"error\":\"invalid_client\"}"));

        RPTExchangeException e = exchangeFailure();

        assertEquals(RPTExchangeException.Reason.EXCHANGE_MISCONFIGURED, e.getReason());
        assertEquals(400, e.getStatus().intValue());
    }

    @Test
    void unknownAudience_classifiesAsMisconfiguration() {
        // What Keycloak answers when the client has no authorization services enabled.
        keycloakThrows(clientError(HttpStatus.BAD_REQUEST,
                "{\"error\":\"invalid_resource\",\"error_description\":\"Resource server not found\"}"));

        assertEquals(RPTExchangeException.Reason.EXCHANGE_MISCONFIGURED, exchangeFailure().getReason());
    }

    @Test
    void nonJsonErrorBody_classifiesAsMisconfiguration() {
        // An HTML error page from a proxy in front of Keycloak: nothing to classify on, so it is ours.
        keycloakThrows(clientError(HttpStatus.NOT_FOUND, "<html><body>404 Not Found</body></html>"));

        assertEquals(RPTExchangeException.Reason.EXCHANGE_MISCONFIGURED, exchangeFailure().getReason());
    }

    @Test
    void serverErrorFromKeycloak_classifiesAsUnavailable() {
        keycloakThrows(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

        RPTExchangeException e = exchangeFailure();

        assertEquals(RPTExchangeException.Reason.KEYCLOAK_UNAVAILABLE, e.getReason());
        assertEquals(500, e.getStatus().intValue());
    }

    @Test
    void connectionRefused_classifiesAsUnavailable() {
        keycloakThrows(new ResourceAccessException("I/O error on POST request", new IOException("Connection refused")));

        RPTExchangeException e = exchangeFailure();

        assertEquals(RPTExchangeException.Reason.KEYCLOAK_UNAVAILABLE, e.getReason());
        assertNull(e.getStatus());
    }

    @Test
    void readTimeout_classifiesAsUnavailable() {
        keycloakThrows(new ResourceAccessException("Read timed out", new SocketTimeoutException("Read timed out")));

        assertEquals(RPTExchangeException.Reason.KEYCLOAK_UNAVAILABLE, exchangeFailure().getReason());
    }

    @Test
    void successWithoutAccessToken_classifiesAsProtocolViolation() {
        keycloakAnswers(Map.of("token_type", "Bearer"));

        RPTExchangeException e = exchangeFailure();

        assertEquals(RPTExchangeException.Reason.PROTOCOL_VIOLATION, e.getReason());
        assertEquals(200, e.getStatus().intValue());
    }

    @Test
    void successWithNoBody_classifiesAsProtocolViolation() {
        keycloakAnswers(null);

        assertEquals(RPTExchangeException.Reason.PROTOCOL_VIOLATION, exchangeFailure().getReason());
    }

    @Test
    void successWithBlankAccessToken_classifiesAsProtocolViolation() {
        keycloakAnswers(Map.of("access_token", "  "));

        assertEquals(RPTExchangeException.Reason.PROTOCOL_VIOLATION, exchangeFailure().getReason());
    }

    @Test
    void failure_neverCarriesTheAccessToken() {
        keycloakThrows(clientError(HttpStatus.UNAUTHORIZED, "{\"error\":\"invalid_token\"}"));

        RPTExchangeException e = exchangeFailure();

        assertFalse(e.getMessage().contains(ACCESS_TOKEN));
        assertFalse(String.valueOf(e.getResponseBody()).contains(ACCESS_TOKEN));
    }
}
