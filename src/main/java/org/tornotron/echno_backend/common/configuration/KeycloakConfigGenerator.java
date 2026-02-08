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
                .replace("${KEYCLOAK_REDIRECT_URI}",formatListString(redirectUri))
                .replace("${KEYCLOAK_WEB_ORIGIN}",formatListString(webOrigin))
                .replace("${KEYCLOAK_FRONTEND_CLIENT_ID}",frontendClientId)
                .replace("${KEYCLOAK_FRONTEND_REDIRECT_URI}",formatListString(frontendRedirectUri))
                .replace("${KEYCLOAK_FRONTEND_WEB_ORIGIN}",formatListString(frontendWebOrigin));

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

    private String formatListString(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String[] items = input.split(",");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.length; i++) {
            sb.append("\"").append(items[i].trim()).append("\"");
            if (i < items.length - 1) {
                sb.append(", ");
            }
        }
        return sb.toString();
    }
}
