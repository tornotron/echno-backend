package org.tornotron.echno_backend.clientconfig;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "What an app needs before it signs in: the identity provider, the public client id each "
        + "app signs in with, and the oldest app versions this backend still serves.")
public record ClientConfigDto(
        @Schema(description = "The identity provider this backend runs on.", example = "workos")
        String provider,
        ClientIds clientIds,
        MinimumVersions minimumVersions) {

    @Schema(description = "Public client ids. None of them is a secret.")
    public record ClientIds(
            @Schema(nullable = true) String web,
            @Schema(nullable = true) String android,
            @Schema(nullable = true) String ios) {
    }

    @Schema(description = "The oldest app version allowed to sign in. Absent means any version.")
    public record MinimumVersions(
            @Schema(nullable = true, example = "2.4.0") String android,
            @Schema(nullable = true, example = "2.4.0") String ios) {
    }
}
