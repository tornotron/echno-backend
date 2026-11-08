package org.tornotron.echno_backend.common.configuration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Provisions the local-development fixture account on startup, where a deployment opts in with
 * {@code echno.dev-fixture.enabled=true}. Off by default: a real instance has no fixture account.
 *
 * <p>The account is named by its subject, the {@code sub} claim the auth provider puts on its
 * tokens. Against the mock issuer in docker-compose.dev.yml that is whatever client id the token
 * was requested with; against a real provider it is the id the provider gave the account.
 *
 * <p>A separate bean from {@link DevFixtureProvisioner} so the call goes through its proxy and its
 * {@code @WithoutTenant} declaration applies.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "echno.dev-fixture.enabled", havingValue = "true")
public class DevFixtureRunner {

    private final DevFixtureProvisioner devFixtureProvisioner;

    @Value("${echno.dev-fixture.subject:}")
    private String subject;

    @Value("${echno.dev-fixture.name:Local Developer}")
    private String name;

    @Value("${echno.dev-fixture.email:}")
    private String email;

    @EventListener
    public void provision(ApplicationReadyEvent event) {
        log.warn("Dev fixture enabled (echno.dev-fixture.enabled=true): provisioning subject '{}' with "
                + "system-admin over its own organization", subject);
        try {
            devFixtureProvisioner.provision(subject, name, email.isBlank() ? null : email);
        } catch (Exception e) {
            log.error("Failed to provision the dev fixture account: {}", e.getMessage(), e);
        }
    }
}
