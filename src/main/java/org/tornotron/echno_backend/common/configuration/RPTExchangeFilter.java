package org.tornotron.echno_backend.common.configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
@Slf4j
public class RPTExchangeFilter extends OncePerRequestFilter {

    /** Seconds a client should wait before retrying once Keycloak is the thing that failed. */
    private static final String RETRY_AFTER_SECONDS = "5";

    private final KeycloakAuthorizationService authorizationService;
    private final RPTCache rptCache;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        String requestUri = request.getRequestURI();

        if(authHeader != null && authHeader.startsWith("Bearer ")) {
            log.debug("Processing request to {} with Bearer token", requestUri);
            String accessToken = authHeader.substring(7);

            try {
                String rptToken = rptCache.getOrMint(accessToken, () -> authorizationService.exchangeForRPT(accessToken));
                log.debug("Successfully obtained RPT token for request to {}", requestUri);

                HttpServletRequest wrappedRequest = new HttpServletRequestWrapper(request) {
                    @Override
                    public String getHeader(String name) {
                        if ("Authorization".equalsIgnoreCase(name)) {
                            return "Bearer " + rptToken;
                        } else {
                            return super.getHeader(name);
                        }
                    }
                };

                filterChain.doFilter(wrappedRequest, response);
            } catch (RPTExchangeException e) {
                rejectClassified(response, requestUri, e);
            } catch (Exception e) {
                log.error("Unexpected failure exchanging token for RPT on request to {}: {}", requestUri, e.getMessage(), e);
                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized",
                        "Authorization could not be established");
            }
        } else {
            log.debug("No Bearer token found for request to {}, continuing without RPT exchange", requestUri);
            filterChain.doFilter(request, response);
        }

    }

    /**
     * The response a classified failure earns: the HTTP status, the OAuth error code the caller can
     * branch on, and a short description. Deliberately says nothing about the deployment.
     */
    private record Rejection(int status, String errorCode, String description) {
    }

    /**
     * Turns a classified exchange failure into the response the caller deserves.
     *
     * <p>A rejected or expired access token is a 401 the caller can act on by re-authenticating. A
     * refused authorization is a 403, because the token was accepted and a fresh one changes nothing. An
     * unreachable Keycloak is a 503, because it is not the caller's fault and re-authenticating against
     * a Keycloak that is down would be exactly the wrong move. A misconfiguration or a protocol break on
     * our side stays a 401 so nothing about the deployment leaks, but it is logged in full at ERROR.
     *
     * <p>This is a switch expression with no default on purpose. The compiler checks it covers every
     * {@link RPTExchangeException.Reason}, so adding a reason without deciding its response breaks the
     * build. A switch statement would instead match nothing at runtime and let the request fall out of
     * the filter as an empty 200, which is the worst answer an auth filter can give.
     */
    private void rejectClassified(HttpServletResponse response, String requestUri, RPTExchangeException e) throws IOException {
        Rejection rejection = switch (e.getReason()) {
            case TOKEN_INVALID -> {
                // Already logged at WARN with the wire detail by the service; keep the per-request line quiet.
                log.debug("Rejecting request to {}: access token rejected by Keycloak", requestUri);
                yield new Rejection(HttpServletResponse.SC_UNAUTHORIZED, "invalid_token",
                        "The access token is expired or invalid");
            }
            case PERMISSION_DENIED -> {
                // 403, not 401: the token authenticated fine, authorization was refused. No
                // WWW-Authenticate either, since presenting a different token is not the remedy.
                log.debug("Rejecting request to {}: Keycloak granted no permissions for the caller", requestUri);
                yield new Rejection(HttpServletResponse.SC_FORBIDDEN, "access_denied",
                        "The access token grants no permissions");
            }
            case KEYCLOAK_UNAVAILABLE -> {
                log.error("Failing request to {} with 503: Keycloak is unavailable (status={})", requestUri, e.getStatus());
                yield new Rejection(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "service_unavailable",
                        "Authorization service is temporarily unavailable");
            }
            case EXCHANGE_MISCONFIGURED, PROTOCOL_VIOLATION -> {
                log.error("Rejecting request to {}: RPT exchange failed as {} (status={}, body={})",
                        requestUri, e.getReason(), e.getStatus(), e.getResponseBody());
                yield new Rejection(HttpServletResponse.SC_UNAUTHORIZED, "unauthorized",
                        "Authorization could not be established");
            }
        };

        writeError(response, rejection.status(), rejection.errorCode(), rejection.description());
    }

    /**
     * Writes a terse OAuth-shaped error body so the caller can branch on the code without being told
     * anything about the deployment. The {@code WWW-Authenticate} header carries the same code on a 401,
     * per RFC 6750, and a 503 carries {@code Retry-After}.
     */
    private void writeError(HttpServletResponse response, int status, String errorCode, String description) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (status == HttpServletResponse.SC_UNAUTHORIZED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"" + errorCode + "\"");
        }
        if (status == HttpServletResponse.SC_SERVICE_UNAVAILABLE) {
            response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }
        response.getWriter().write("{\"error\":\"" + errorCode + "\",\"error_description\":\"" + description + "\"}");
    }
}
