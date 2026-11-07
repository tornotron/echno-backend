package org.tornotron.echno_backend.clientconfig;

import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * The client configuration, read once from settings. Each app's client id falls back to the provider's
 * own public client: {@code WORKOS_CLIENT_ID} under WorkOS, the frontend client under Keycloak. A minimum
 * version that is set must be numbers separated by dots, or the backend refuses to start.
 */
@Component
public class ClientConfigSettings {

    private static final Pattern VERSION = Pattern.compile("\\d+(\\.\\d+){0,3}");

    private final ClientConfigDto dto;

    public ClientConfigSettings(Environment environment) {
        String provider = environment.getProperty("echno.identity.provider", "keycloak").trim();
        String providerClient = blankToNull(environment.getProperty(switch (provider) {
            case "workos" -> "echno.identity.workos.client-id";
            case "keycloak" -> "keycloak.frontend.client-id";
            default -> "echno.client-config.none";
        }));
        String web = or(environment.getProperty("echno.client-config.web-client-id"), providerClient);
        String android = or(environment.getProperty("echno.client-config.android-client-id"), providerClient);
        String ios = or(environment.getProperty("echno.client-config.ios-client-id"), providerClient);
        this.dto = new ClientConfigDto(provider,
                new ClientConfigDto.ClientIds(web, android, ios),
                new ClientConfigDto.MinimumVersions(
                        version(environment, "echno.client-config.minimum-android-version"),
                        version(environment, "echno.client-config.minimum-ios-version")));
    }

    ClientConfigDto toDto() {
        return dto;
    }

    @Nullable
    private static String version(Environment environment, String name) {
        String value = blankToNull(environment.getProperty(name));
        if (value != null && !VERSION.matcher(value).matches()) {
            throw new IllegalStateException(name + " must be a version such as 2.4.0, not '" + value + "'");
        }
        return value;
    }

    @Nullable
    private static String or(@Nullable String value, @Nullable String fallback) {
        String set = blankToNull(value);
        return set != null ? set : fallback;
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
