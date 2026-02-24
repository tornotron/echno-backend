package org.tornotron.echno_backend.common.configuration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Map;


@Service
@Slf4j
public class KeycloakAuthorizationService {

    @Value("${keycloak.issuer-uri}")
    private String issuerUri;

    @Value("${jwt.auth.converter.resource-id}")
    private String clientId;

    private final RestTemplate restTemplate = new RestTemplate();

    public String exchangeForRPT(String accessToken) {
        String tokenEndpoint = issuerUri + "/protocol/openid-connect/token";

        log.debug("Attempting to exchange access token for RPT at endpoint: {}", tokenEndpoint);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBearerAuth(accessToken);

        MultiValueMap<String ,String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "urn:ietf:params:oauth:grant-type:uma-ticket");
        params.add("audience", clientId);

        HttpEntity<MultiValueMap<String,String>> request = new HttpEntity<>(params, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(tokenEndpoint, request, Map.class);
            if (response.getBody() != null && response.getBody().containsKey("access_token")) {
                String rptToken = (String) response.getBody().get("access_token");
                log.debug("Successfully exchanged access token for RPT");
                return rptToken;
            } else {
                log.error("RPT exchange response missing access_token field");
                throw new RuntimeException("Invalid RPT response from Keycloak");
            }
        } catch (Exception e) {
            log.error("Failed to exchange access token for RPT: {}", e.getMessage());
            throw new RuntimeException("Failed to obtain RPT token for authorization", e);
        }
    }
}
