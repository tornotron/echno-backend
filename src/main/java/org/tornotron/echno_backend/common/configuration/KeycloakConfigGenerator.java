package org.tornotron.echno_backend.common.configuration;

import jakarta.annotation.PostConstruct;
import org.aspectj.apache.bcel.util.ClassPath;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

@Component
public class KeycloakConfigGenerator {

    @Value("${keycloak.client-id}")
    private String clientId;

    // Secret of the confidential backend client. It is written into the generated realm JSON so a
    // freshly created realm is born with the secret this deployment already holds, rather than a
    // Keycloak-generated random one that nothing else knows. Without it, KeycloakGroupService's
    // client_credentials grant is rejected as unauthorized_client from the first boot of any rebuilt
    // environment, and employee onboarding and org-role assignment fail for everyone.
    @Value("${keycloak.secret}")
    private String clientSecret;

    @Value("${keycloak.redirect-uri}")
    private String redirectUri;

    @Value("${keycloak.web-origin}")
    private String webOrigin;

    @Value("${keycloak.frontend.client-id}")
    private String frontendClientId;

    @Value("${keycloak.frontend.redirect-uri}")
    private String frontendRedirectUri;

    @Value("${keycloak.frontend.web-origin}")
    private String frontendWebOrigin;

    @Value("${keycloak.admin.userName}")
    private String adminUsername;

    @Value("${keycloak.admin.email}")
    private String adminEmail;

    @Value("${keycloak.admin.password}")
    private String adminPassword;

    @Value("${keycloak.admin.firstName}")
    private String adminFirstName;

    @Value("${keycloak.admin.lastName}")
    private String adminLastName;

    @Value("${keycloak.service.userName}")
    private String serviceUsername;

    @Value("${keycloak.service.email}")
    private String serviceEmail;

    @Value("${keycloak.service.password}")
    private String servicePassword;

    @Value("${keycloak.service.firstName}")
    private String serviceFirstName;

    @Value("${keycloak.service.lastName}")
    private String serviceLastName;

    @Value("${keycloak.registration-allowed}")
    private boolean registrationAllowed;

    @Value("${keycloak.config.output-path}")
    private String outputPath;

    @PostConstruct
    public void generateConfigs() {
        try {
            generateRealmConfig();
            generateUsersConfig();
            System.out.println("Keycloak configuration files generated successfully");

        } catch (IOException e) {
            throw new RuntimeException("Failed to generate keycloak config files",e);
        }
    }

    private void generateRealmConfig() throws IOException {
        String template = readTemplate("init-keycloak.json.template");

        String config = template
                .replace("${KEYCLOAK_CLIENT_ID}",clientId)
                .replace("${KEYCLOAK_CLIENT_SECRET}",escapeJson(clientSecret))
                .replace("${KEYCLOAK_REDIRECT_URI}",formatListString(redirectUri))
                .replace("${KEYCLOAK_WEB_ORIGIN}",formatListString(webOrigin))
                .replace("${KEYCLOAK_FRONTEND_CLIENT_ID}",frontendClientId)
                .replace("${KEYCLOAK_FRONTEND_REDIRECT_URI}",formatListString(frontendRedirectUri))
                .replace("${KEYCLOAK_FRONTEND_WEB_ORIGIN}",formatListString(frontendWebOrigin))
                .replace("${KEYCLOAK_REGISTRATION_ALLOWED}",String.valueOf(registrationAllowed));

        writeConfig("init-keycloak.json",config);

    }

    private void generateUsersConfig() throws IOException {
        String template = readTemplate("init-keycloak-users.json.template");

        String config = template
                .replace("${ADMIN_USERNAME}",adminUsername)
                .replace("${ADMIN_EMAIL}",adminEmail)
                .replace("${ADMIN_PASSWORD}",adminPassword)
                .replace("${ADMIN_FIRST_NAME}",adminFirstName)
                .replace("${ADMIN_LAST_NAME}",adminLastName)
                .replace("${SERVICE_USERNAME}",serviceUsername)
                .replace("${SERVICE_EMAIL}",serviceEmail)
                .replace("${SERVICE_PASSWORD}",servicePassword)
                .replace("${SERVICE_FIRST_NAME}",serviceFirstName)
                .replace("${SERVICE_LAST_NAME}",serviceLastName);

        writeConfig("init-keycloak-users.json",config);
    }

    private String readTemplate(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource("keycloak-templates/"+filename);
        return new String(resource.getInputStream().readAllBytes());
    }

    private void writeConfig(String filename, String content) throws IOException {
        Path outputDir = Paths.get(outputPath);
        if(!Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
        }

        Path outputFile = outputDir.resolve(filename);
        Files.writeString(outputFile, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Escapes a value for embedding inside a JSON string literal. Configured values reach the
     * templates verbatim, so a value carrying a quote or a backslash would otherwise produce a
     * malformed realm file. Never log the argument or the result: this is used for secrets.
     */
    static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private String formatListString(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String[] items = input.split(",");
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String item : items) {
            String trimmed = item.trim();
            // Skip blank entries so an unset optional origin (e.g. a defaulted-empty
            // localhost var) does not produce an empty-string webOrigin.
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!first) {
                sb.append(", ");
            }
            sb.append("\"").append(trimmed).append("\"");
            first = false;
        }
        return sb.toString();
    }
}
