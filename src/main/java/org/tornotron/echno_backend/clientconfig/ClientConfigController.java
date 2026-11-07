package org.tornotron.echno_backend.clientconfig;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * The one public read an app makes before it has a token. It answers from settings alone and reaches
 * no data, which {@code PublicEndpointTenantExposureTest} holds it to.
 */
@RestController
@RequestMapping("/api/v1/public")
@Tag(name = "Client configuration", description = "Settings an app reads before signing in. No authentication.")
public class ClientConfigController {

    private final ClientConfigSettings settings;

    public ClientConfigController(ClientConfigSettings settings) {
        this.settings = settings;
    }

    @GetMapping("/client-config")
    @PreAuthorize("permitAll()")
    @SecurityRequirements
    @Operation(summary = "Read the client configuration",
            description = "The identity provider, the public client id each app signs in with, and the minimum "
                    + "Android and iOS versions. An app older than its minimum asks the person to update.")
    public ResponseEntity<ClientConfigDto> clientConfig() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(settings.toDto());
    }
}
