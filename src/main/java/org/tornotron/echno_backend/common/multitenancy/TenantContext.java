package org.tornotron.echno_backend.common.multitenancy;

public final class TenantContext {

    private static final ThreadLocal<Long> currentOrgId = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> bypassed = ThreadLocal.withInitial(() -> false);

    private TenantContext() {}

    public static void setCurrentOrgId(Long orgId) {
        currentOrgId.set(orgId);
    }

    public static Long getCurrentOrgId() {
        return currentOrgId.get();
    }

    public static void setBypass(boolean bypass) {
        bypassed.set(bypass);
    }

    public static boolean isBypassed() {
        return Boolean.TRUE.equals(bypassed.get());
    }

    public static void clear() {
        currentOrgId.remove();
        bypassed.remove();
    }
}
