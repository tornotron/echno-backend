package org.tornotron.echno_backend.risk;

/**
 * The two guards on every risk register endpoint, one string each so the twin controllers cannot
 * drift.
 *
 * <p>The register sits with its project, so it follows the project's own split: any member of
 * the organization reads it (the site team needs to see the risks it works under), and the system
 * administrator and the project manager, who maintain the project, record and change risks.
 */
public final class RiskAccess {

    public static final String READ_GUARD = "@orgSecurity.isMemberOfCurrentTenant()";
    public static final String MANAGE_GUARD =
            "@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')";

    private RiskAccess() {
    }
}
