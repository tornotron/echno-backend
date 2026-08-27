package org.tornotron.echno_backend.common.configuration;

import org.springframework.lang.Nullable;

/**
 * Raised by {@link KeycloakAuthorizationService#exchangeForRPT(String)} when the UMA ticket exchange
 * against Keycloak does not yield a Requesting Party Token.
 *
 * <p>The exchange runs on every authenticated request, so it fails for very different reasons that
 * need very different handling: a caller arriving with a stale access token is routine, an unreachable
 * Keycloak is an outage, and a bad client registration is our own misconfiguration. Those used to be
 * indistinguishable in the log and in the response. The {@link Reason} carried here is what lets
 * {@link RPTExchangeFilter} pick the right HTTP status and the right log level for each.
 *
 * <p>The exception carries Keycloak's HTTP status and response body when there was one, so the cause
 * of a failed exchange can be read straight off a single log line. Neither the incoming access token,
 * the minted RPT, nor any client credential is ever stored on it.
 */
public class RPTExchangeException extends RuntimeException {

    /**
     * Why the exchange failed, and therefore who owns the problem.
     */
    public enum Reason {

        /**
         * The caller's access token was rejected: expired, revoked or otherwise not valid. Keycloak
         * answers 401, or an OAuth error body of {@code invalid_token} or {@code invalid_grant}.
         * The caller has to obtain a fresh token; nothing is wrong on this side.
         */
        TOKEN_INVALID,

        /**
         * The token was accepted but Keycloak refused to grant any permission for it
         * ({@code access_denied}). A live authorization decision, not a broken token, so re-authenticating
         * will not help the caller.
         */
        PERMISSION_DENIED,

        /**
         * The exchange itself is misconfigured: unknown or wrong client, an audience Keycloak does not
         * recognise, bad client credentials, or authorization services not enabled on the client. Ours
         * to fix, and invisible to the caller.
         */
        EXCHANGE_MISCONFIGURED,

        /**
         * Keycloak could not be reached or did not answer: connection refused, DNS failure, a read or
         * connect timeout, or a 5xx from Keycloak itself. A dependency outage, so the request is not
         * the caller's fault and should not be answered as an authentication failure.
         */
        KEYCLOAK_UNAVAILABLE,

        /**
         * Keycloak answered successfully but the payload was not a token response: 2xx with no
         * {@code access_token} field. Either a proxy sitting in front of the token endpoint or a
         * protocol change on Keycloak's side.
         */
        PROTOCOL_VIOLATION
    }

    private final Reason reason;
    private final Integer status;
    private final String responseBody;

    /**
     * @param reason       the classified cause of the failure
     * @param status       Keycloak's HTTP status, or {@code null} when no response was received
     * @param responseBody Keycloak's response body, or {@code null} when there was none. Callers pass
     *                     the OAuth error payload only; it never carries bearer material
     * @param message      a short, log-safe description of what failed
     * @param cause        the underlying client exception, or {@code null}
     */
    public RPTExchangeException(Reason reason,
                                @Nullable Integer status,
                                @Nullable String responseBody,
                                String message,
                                @Nullable Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.status = status;
        this.responseBody = responseBody;
    }

    public RPTExchangeException(Reason reason, @Nullable Integer status, @Nullable String responseBody, String message) {
        this(reason, status, responseBody, message, null);
    }

    public Reason getReason() {
        return reason;
    }

    /** Keycloak's HTTP status, or {@code null} when Keycloak never answered. */
    @Nullable
    public Integer getStatus() {
        return status;
    }

    /** Keycloak's response body, or {@code null} when there was none. */
    @Nullable
    public String getResponseBody() {
        return responseBody;
    }
}
