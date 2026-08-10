package org.tornotron.echno_backend.common.exception;

/**
 * Thrown when a tenant-scoped entity belonging to a different organization is
 * loaded while a tenant context is active. This is the fail-closed backstop for
 * cross-tenant reads that the Hibernate org filter cannot cover (primary-key
 * loads, and any path where the filter was not enabled).
 */
public class TenantAccessDeniedException extends RuntimeException {

    public TenantAccessDeniedException(String message) {
        super(message);
    }
}
