package org.tornotron.echno_backend.common.exception;

/**
 * Thrown when work reaches tenant-scoped data with no tenant scope declared at all: no
 * organization id, no {@code @BypassTenantFilter}, no {@code @WithoutTenant}.
 *
 * <p>It is not the caller asking for another organization's row, which is
 * {@link TenantAccessDeniedException} proper. It is the application unable to say which
 * organization the work belongs to, and refusing to guess. Before #507 that condition returned
 * every organization's rows and logged nothing.
 *
 * <p>It extends {@link TenantAccessDeniedException} so a request that hits it answers 403 with
 * the same non-committal body as any other isolation refusal, rather than describing the internal
 * state to the caller. The detail belongs in the log, which
 * {@code org.tornotron.echno_backend.common.multitenancy.UnscopedAccessGuard} writes.
 */
public class UnscopedTenantAccessException extends TenantAccessDeniedException {

    public UnscopedTenantAccessException(String message) {
        super(message);
    }
}
