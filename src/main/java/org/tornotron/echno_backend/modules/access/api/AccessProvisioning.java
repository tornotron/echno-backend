package org.tornotron.echno_backend.modules.access.api;

/**
 * Sets up access for a newly created organization.
 *
 * <p>Called once, inside the transaction that creates the organization, so an organization never
 * exists without its roles and an owner who can administer it. Safe to call again: anything
 * already in place is left as it is.
 */
public interface AccessProvisioning {

    /**
     * Gives the organization its starting roles and makes {@code ownerEmployeeId}, the creator's
     * membership, its administrator.
     */
    void provisionOrganization(Long organizationId, Long ownerEmployeeId);
}
