package org.tornotron.echno_backend.keycloak.dto;

import lombok.Data;
import java.util.List;

@Data
public class ClientCreationDto {
    private String clientId;
    private String name;
    private String description;
    private String rootUrl;
    private String adminUrl;
    private String baseUrl;
    private List<String> redirectUris;
    private List<String> webOrigins;
    private boolean publicClient;
    private boolean bearerOnly;
    private boolean serviceAccountsEnabled;
    private boolean standardFlowEnabled;
    private boolean implicitFlowEnabled;
    private boolean directAccessGrantsEnabled;
}
