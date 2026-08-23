package org.tornotron.echno_backend.common.configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Central OpenAPI document metadata and the bearer-token security scheme.
 * <p>
 * The API is protected by JWT access tokens issued by Keycloak. Declaring the
 * scheme here lets the Swagger UI "Authorize" dialog attach a bearer token to
 * requests, and applies it as the default requirement for every operation.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI echnoOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Echno Backend API")
                        .description("REST API for the Echno construction management platform. "
                                + "It covers employees, projects, tasks, attendance, inventory and goods "
                                + "receipts, issue tracking, PDF reporting, and a double-entry construction "
                                + "finance ledger. All endpoints are tenant scoped and require a Keycloak "
                                + "bearer token unless documented otherwise.")
                        .version("v1")
                        .contact(new Contact().name("Echno").url("https://echno.xyz").email("info@echno.xyz"))
                        .license(new License().name("AGPL-3.0").url("https://www.gnu.org/licenses/agpl-3.0.html")))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Keycloak-issued JWT access token. Paste the raw token (without the "
                                + "\"Bearer\" prefix) into the Authorize dialog.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
