package org.tornotron.echno_backend.organization;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.tornotron.echno_backend.modules.access.api.AccessStartingRoles;
import org.tornotron.echno_backend.organization.dto.OrganizationCreationDto;

import java.time.LocalDateTime;

/**
 * What every new organization gets, whoever starts it: the row, its starting roles, and, once the
 * transaction commits, its finance defaults. It needs no current user, which is what lets the first
 * organization of a new stack be started by the system.
 *
 * <p>The caller's own steps stay with the caller: the provider organization, the creator's
 * membership and role, billing.
 */
@Component
public class OrganizationProvisioning {

    private final OrganizationRepository repository;
    private final AccessStartingRoles startingRoles;
    private final OrganizationOnboardingSeeder onboardingSeeder;

    public OrganizationProvisioning(OrganizationRepository repository, AccessStartingRoles startingRoles,
                                    OrganizationOnboardingSeeder onboardingSeeder) {
        this.repository = repository;
        this.startingRoles = startingRoles;
        this.onboardingSeeder = onboardingSeeder;
    }

    /**
     * Creates the organization inside the caller's transaction.
     *
     * @param creatorUserId the user who started it, or null when the system did.
     */
    public Organization provision(OrganizationCreationDto details, @Nullable Long creatorUserId) {
        Organization organization = new Organization();
        organization.setOrganizationName(details.getOrganizationName());
        organization.setOrganizationAddress(details.getOrganizationAddress());
        organization.setCreatedAt(LocalDateTime.now());
        organization.setOrganizationEmail(details.getOrganizationEmail());
        organization.setOrganizationPhone(details.getOrganizationPhone());
        organization.setOrganizationWebsite(details.getOrganizationWebsite());
        organization.setOrganizationLogo(details.getOrganizationLogo());
        organization.setCreatorId(creatorUserId == null ? null : creatorUserId.intValue());
        organization.setIsActive(true);
        Organization saved = repository.save(organization);
        startingRoles.ensureStartingRoles(saved.getId());
        // Seeded after this transaction commits, so a seed failure is logged and skipped rather than
        // rolling the organization back, and each idempotent seeder sees the committed organization.
        afterCommit(() -> onboardingSeeder.seedFinanceDefaults(saved.getId()));
        return saved;
    }

    /** Runs the work after the current transaction commits, or now when there is none. */
    static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
