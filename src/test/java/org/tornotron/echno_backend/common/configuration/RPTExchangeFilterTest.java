package org.tornotron.echno_backend.common.configuration;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RPTExchangeFilter} + {@link RPTCache}. A real {@link RPTCache} is wired to a
 * mocked {@link KeycloakAuthorizationService}, so these assert the caching contract end to end (the
 * Keycloak exchange runs once per distinct access token, not once per request) and the mapping from a
 * classified {@link RPTExchangeException} to the status and body the caller sees.
 */
class RPTExchangeFilterTest {

    // HS256 needs a >= 256-bit (32-byte) secret; the signature is never verified, only parsed.
    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes();

    private KeycloakAuthorizationService authorizationService;
    private RPTExchangeFilter filter;

    @BeforeEach
    void setUp() {
        authorizationService = mock(KeycloakAuthorizationService.class);
        filter = new RPTExchangeFilter(authorizationService, new RPTCache());
    }

    private static String jwtWithJti(String jti) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .jwtID(jti)
                .subject("user")
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build();
        SignedJWT signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        signed.sign(new MACSigner(SECRET));
        return signed.serialize();
    }

    /** One filtered request: the mocked chain it reached (or did not) and the response it produced. */
    private record Exchange(FilterChain chain, MockHttpServletResponse response) {
    }

    private Exchange invoke(String accessToken) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/projects");
        request.addHeader("Authorization", "Bearer " + accessToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        return new Exchange(chain, response);
    }

    /** Drives one request whose RPT mint fails with {@code reason}, and returns the resulting response. */
    private MockHttpServletResponse responseForFailure(RPTExchangeException.Reason reason, Integer status) throws Exception {
        String token = jwtWithJti(UUID.randomUUID().toString());
        when(authorizationService.exchangeForRPT(token))
                .thenThrow(new RPTExchangeException(reason, status, "{\"error\":\"whatever\"}", "exchange failed"));
        return invoke(token).response();
    }

    @Test
    void sameAccessToken_mintsRptOnlyOnce() throws Exception {
        String token = jwtWithJti(UUID.randomUUID().toString());
        when(authorizationService.exchangeForRPT(token)).thenReturn("rpt-value");

        invoke(token);
        invoke(token);

        // Two requests, one Keycloak round trip: the second request is served from the cache.
        verify(authorizationService, times(1)).exchangeForRPT(token);
    }

    @Test
    void differentAccessTokens_eachMint() throws Exception {
        String tokenA = jwtWithJti(UUID.randomUUID().toString());
        String tokenB = jwtWithJti(UUID.randomUUID().toString());
        when(authorizationService.exchangeForRPT(anyString())).thenReturn("rpt-value");

        invoke(tokenA);
        invoke(tokenB);

        // Distinct tokens must never share an RPT: each mints exactly once.
        verify(authorizationService, times(1)).exchangeForRPT(tokenA);
        verify(authorizationService, times(1)).exchangeForRPT(tokenB);
    }

    @Test
    void failedMint_isNotCached_andRetriesOnNextRequest() throws Exception {
        String token = jwtWithJti(UUID.randomUUID().toString());
        when(authorizationService.exchangeForRPT(token))
                .thenThrow(new RuntimeException("keycloak unavailable"))
                .thenReturn("rpt-value");

        invoke(token); // first mint fails -> nothing cached, request rejected as today
        invoke(token); // second request must mint again, not serve a cached failure

        verify(authorizationService, times(2)).exchangeForRPT(token);
    }

    @Test
    void wrappedRequest_carriesRptInAuthorizationHeader() throws Exception {
        String token = jwtWithJti(UUID.randomUUID().toString());
        when(authorizationService.exchangeForRPT(token)).thenReturn("rpt-value");

        FilterChain chain = invoke(token).chain();

        ArgumentCaptor<HttpServletRequest> captor = ArgumentCaptor.forClass(HttpServletRequest.class);
        verify(chain).doFilter(captor.capture(), any());
        assertEquals("Bearer rpt-value", captor.getValue().getHeader("Authorization"));
    }

    @Test
    void expiredAccessToken_isRejectedAs401InvalidToken() throws Exception {
        MockHttpServletResponse response = responseForFailure(RPTExchangeException.Reason.TOKEN_INVALID, 401);

        assertEquals(401, response.getStatus());
        // Machine-readable so the caller can tell "get a fresh token" apart from "you are not allowed".
        assertTrue(response.getContentAsString().contains("\"error\":\"invalid_token\""));
        assertEquals("Bearer error=\"invalid_token\"", response.getHeader("WWW-Authenticate"));
    }

    @Test
    void permissionDenied_isRejectedAs403AccessDenied() throws Exception {
        MockHttpServletResponse response = responseForFailure(RPTExchangeException.Reason.PERMISSION_DENIED, 403);

        // The token authenticated; only the authorization decision went against the caller.
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"error\":\"access_denied\""));
        // Re-authenticating is not the remedy, so the caller is not challenged for a new token.
        assertNull(response.getHeader("WWW-Authenticate"));
    }

    @Test
    void keycloakUnreachable_isRejectedAs503() throws Exception {
        // Not the caller's fault: a client that answered a 401 here by re-authenticating would be
        // hammering a Keycloak that is already down.
        MockHttpServletResponse response = responseForFailure(RPTExchangeException.Reason.KEYCLOAK_UNAVAILABLE, null);

        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"error\":\"service_unavailable\""));
        assertEquals("5", response.getHeader("Retry-After"));
    }

    @Test
    void keycloakServerError_isRejectedAs503() throws Exception {
        MockHttpServletResponse response = responseForFailure(RPTExchangeException.Reason.KEYCLOAK_UNAVAILABLE, 500);

        assertEquals(503, response.getStatus());
    }

    @Test
    void misconfiguredExchange_staysA401AndLeaksNothing() throws Exception {
        MockHttpServletResponse response = responseForFailure(RPTExchangeException.Reason.EXCHANGE_MISCONFIGURED, 400);

        assertEquals(401, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("\"error\":\"unauthorized\""));
        // Keycloak's own error payload must never reach the caller.
        assertFalse(body.contains("whatever"));
    }

    @Test
    void protocolViolation_staysA401() throws Exception {
        MockHttpServletResponse response = responseForFailure(RPTExchangeException.Reason.PROTOCOL_VIOLATION, 200);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"error\":\"unauthorized\""));
    }

    @Test
    void everyClassifiedReason_producesAnErrorStatusAndBody() throws Exception {
        // Iterates the enum rather than listing the reasons, so a Reason added without a branch in
        // rejectClassified fails here as well as at the compiler. An unhandled reason would fall out of
        // the filter as an empty 200: an authorization failure answered as success, with nothing written.
        for (RPTExchangeException.Reason reason : RPTExchangeException.Reason.values()) {
            MockHttpServletResponse response = responseForFailure(reason, 400);
            String body = response.getContentAsString();

            assertTrue(response.getStatus() >= 400, reason + " must not answer with a success status");
            assertFalse(body.isBlank(), reason + " must write an error body");
            assertTrue(body.contains("\"error\":\""), reason + " must write a machine-readable error code");
        }
    }

    @Test
    void unclassifiedFailure_staysA401() throws Exception {
        String token = jwtWithJti(UUID.randomUUID().toString());
        when(authorizationService.exchangeForRPT(token)).thenThrow(new IllegalStateException("boom"));

        Exchange exchange = invoke(token);

        assertEquals(401, exchange.response().getStatus());
        assertTrue(exchange.response().getContentAsString().contains("\"error\":\"unauthorized\""));
    }

    @Test
    void failedExchange_neverReachesTheChain() throws Exception {
        String token = jwtWithJti(UUID.randomUUID().toString());
        when(authorizationService.exchangeForRPT(token))
                .thenThrow(new RPTExchangeException(RPTExchangeException.Reason.TOKEN_INVALID, 401, null, "expired"));

        Exchange exchange = invoke(token);

        verify(exchange.chain(), never()).doFilter(any(), any());
    }

    @Test
    void requestWithoutBearerToken_passesStraightThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/public/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(authorizationService, never()).exchangeForRPT(anyString());
        assertEquals(200, response.getStatus());
    }

    @Test
    void actuatorRequests_areNotFiltered() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/actuator/health");

        assertTrue(filter.shouldNotFilter(request));
    }
}
