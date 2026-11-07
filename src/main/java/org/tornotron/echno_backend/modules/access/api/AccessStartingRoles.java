package org.tornotron.echno_backend.modules.access.api;

/** Gives an organization the starting roles. The rest of the code calls this when it creates one. */
public interface AccessStartingRoles {

    /** Copies any starting role the organization does not have yet, inside the caller's transaction. */
    void ensureStartingRoles(Long organizationId);
}
