package org.tornotron.echno_backend.common.identity.workos;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/**
 * The WorkOS environment this stack signs in with.
 *
 * @param apiKey   the environment's secret API key. Never logged.
 * @param clientId the Application's client id. Signing keys and the default issuer are derived from it.
 * @param apiUrl   the API's address, {@code https://api.workos.com} unless a test points it elsewhere.
 */
@ConfigurationProperties("echno.identity.workos")
public record WorkOsSettings(String apiKey, String clientId, URI apiUrl) {

    /** Where the Application's signing keys are published. It answers with no credentials. */
    public URI jwksUri() {
        return apiUrl.resolve("/sso/jwks/" + clientId);
    }

    /**
     * The issuer WorkOS writes into this Application's tokens on its own domain. A custom auth domain
     * changes what tokens carry, but not this value, which is why {@link WorkOsTokenMapper} stores it.
     */
    public String defaultIssuer() {
        return apiUrl.resolve("/user_management/" + clientId).toString();
    }

    @Override
    public String toString() {
        return "WorkOsSettings[clientId=" + clientId + ", apiUrl=" + apiUrl + "]";
    }
}
