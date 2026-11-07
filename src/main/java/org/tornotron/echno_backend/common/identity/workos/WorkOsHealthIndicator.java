package org.tornotron.echno_backend.common.identity.workos;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Whether WorkOS accepts this stack's API key, by the cheapest authenticated read. It replaces the
 * deployment's check of the Keycloak client secret. A refused key is down; so is an unreachable API.
 * Neither the key nor any response body appears in the details.
 */
public class WorkOsHealthIndicator implements HealthIndicator {

    private final WorkOsApi api;

    WorkOsHealthIndicator(WorkOsApi api) {
        this.api = api;
    }

    @Override
    public Health health() {
        try {
            api.listOneOrganization();
            return Health.up().build();
        } catch (WorkOsApiException refused) {
            Health.Builder down = Health.down().withDetail("status", refused.status());
            if (refused.status() == 401 || refused.status() == 403) {
                down.withDetail("reason", "the API key was refused");
            }
            return down.build();
        } catch (RuntimeException unreachable) {
            return Health.down().withDetail("reason", "WorkOS could not be reached").build();
        }
    }
}
