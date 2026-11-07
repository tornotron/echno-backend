package org.tornotron.echno_backend.common.identity.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.tornotron.echno_backend.common.identity.Principal;

class KeycloakTokenMapperTest {

    private final KeycloakTokenMapper mapper = new KeycloakTokenMapper();

    private static Jwt tokenFrom(String issuer) {
        return Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .claim("iss", issuer)
                .subject("subject-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    @Test
    void theInternalAndPublicAddressesOfARealmGiveOneIssuer() {
        Principal internal = mapper.toPrincipal(tokenFrom("http://keycloak:8080/realms/echno"));
        Principal publicAddress = mapper.toPrincipal(tokenFrom("https://auth.example.test/realms/echno"));

        assertThat(internal.issuer()).isEqualTo(publicAddress.issuer()).isEqualTo(mapper.issuer());
    }

    @Test
    void aTokenWithNoIssuerStillMaps() {
        Jwt token = Jwt.withTokenValue("t").header("alg", "RS256").subject("subject-1").build();

        assertThat(mapper.toPrincipal(token).issuer()).isEqualTo(mapper.issuer());
    }
}
