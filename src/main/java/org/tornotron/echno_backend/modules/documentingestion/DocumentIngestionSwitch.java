package org.tornotron.echno_backend.modules.documentingestion;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Reads the operator switch and the organization allow-list. The default of off and of empty is
 * here in code, so losing the lines from {@code application.yml} fails closed. A value that does
 * not parse counts as off or as absent and never as an error: this module must not be able to stop
 * the application from starting.
 */
public final class DocumentIngestionSwitch {

    public static final String ALLOWED_ORGS_PROPERTY =
            "echno.modules." + DocumentIngestionModule.ID + ".allowed-org-ids";

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionSwitch.class);

    private final Environment environment;
    private final Set<Long> allowedOrgIds;

    public DocumentIngestionSwitch(Environment environment) {
        this.environment = environment;
        this.allowedOrgIds = parse(environment.getProperty(ALLOWED_ORGS_PROPERTY, ""));
    }

    public boolean isOn() {
        return "true".equalsIgnoreCase(environment.getProperty(DocumentIngestionModuleEnabled.PROPERTY, "false").trim());
    }

    public boolean allows(Long organizationId) {
        return organizationId != null && allowedOrgIds.contains(organizationId);
    }

    private static Set<Long> parse(String raw) {
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .flatMap(entry -> {
                    try {
                        return java.util.stream.Stream.of(Long.valueOf(entry));
                    } catch (NumberFormatException e) {
                        log.warn("Ignoring '{}' in {}: not an organization id", entry, ALLOWED_ORGS_PROPERTY);
                        return java.util.stream.Stream.empty();
                    }
                })
                .collect(Collectors.toUnmodifiableSet());
    }
}
