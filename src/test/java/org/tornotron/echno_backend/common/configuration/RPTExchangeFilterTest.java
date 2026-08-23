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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RPTExchangeFilter} + {@link RPTCache}. A real {@link RPTCache} is wired to a
 * mocked {@link KeycloakAuthorizationService}, so these assert the caching contract end to end: the
 * Keycloak exchange runs once per distinct access token, not once per request.
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

    private FilterChain invoke(String accessToken) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/projects");
        request.addHeader("Authorization", "Bearer " + accessToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        return chain;
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

        FilterChain chain = invoke(token);

        ArgumentCaptor<HttpServletRequest> captor = ArgumentCaptor.forClass(HttpServletRequest.class);
        verify(chain).doFilter(captor.capture(), any());
        assertEquals("Bearer rpt-value", captor.getValue().getHeader("Authorization"));
    }
}
