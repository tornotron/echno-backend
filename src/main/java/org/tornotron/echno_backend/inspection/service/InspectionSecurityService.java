package org.tornotron.echno_backend.inspection.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.inspection.NcrType;
import org.tornotron.echno_backend.inspection.domain.Ncr;
import org.tornotron.echno_backend.inspection.repositories.NcrRepository;

import java.util.UUID;

/**
 * Inspection authorization policy, kept in one place so the role model can be
 * retuned without touching the controllers, following the
 * {@code attendanceSecurity} precedent. Controllers reference this bean from
 * {@code @PreAuthorize} (for example
 * {@code @inspectionSecurity.canSignOffNcr(#id)}).
 *
 * <p>The module used to admit only {@code system-admin} and
 * {@code project-manager} on every endpoint, which collapsed four jobs into one
 * and made the closure trail on an NCR meaningless: whoever could raise it could
 * also close it. The defaults below give each job the authority the functional
 * spec assigns it:
 *
 * <ul>
 *   <li>{@code echno.security.inspection.read-roles} (default
 *       {@code system-admin,project-manager,qa-engineer,safety-officer,site-engineer})
 *       reads inspections, checklists and NCRs. The project manager's full read and
 *       the site engineer's view of the NCRs assigned to them are both here.</li>
 *   <li>{@code echno.security.inspection.manage-roles} (default
 *       {@code system-admin,project-manager,qa-engineer,safety-officer}) schedules
 *       and records inspections. Not the site engineer: they act on the
 *       non-conformances an inspection raises, they do not carry it out.</li>
 *   <li>{@code echno.security.inspection.checklist-roles} (default
 *       {@code system-admin,qa-engineer}) defines the criteria work is judged
 *       against. Deliberately narrow: this is the QA engineer's job, and setting a
 *       tolerance is a bigger authority than recording a measurement against one.
 *       The safety officer is not here yet because a checklist template is keyed by
 *       trade, which is the QA/QC axis; safety criteria arrive with the safety
 *       phase and this default moves with them.</li>
 *   <li>{@code echno.security.inspection.ncr-raise-roles} (default
 *       {@code system-admin,qa-engineer,safety-officer}) raises and assigns
 *       non-conformances. The project manager reads them and does not assign them,
 *       which is what the spec's approval-visibility line means.</li>
 *   <li>{@code echno.security.inspection.corrective-action-roles} (default
 *       {@code system-admin,site-engineer}) reports the corrective work done. This
 *       is as far as the assignee takes it.</li>
 *   <li>{@code echno.security.inspection.quality-signoff-roles} (default
 *       {@code system-admin,qa-engineer}) and
 *       {@code echno.security.inspection.safety-signoff-roles} (default
 *       {@code system-admin,safety-officer}) verify, reject, reopen and close, each
 *       only for their own discipline. See {@link #canSignOffNcr}.</li>
 * </ul>
 *
 * <p>Override any of them per environment as a comma-separated list of org-role
 * tokens, no code change needed.
 */
@Service("inspectionSecurity")
public class InspectionSecurityService {

    private final OrganizationSecurityService orgSecurity;
    private final NcrRepository ncrRepo;
    private final String[] readRoles;
    private final String[] manageRoles;
    private final String[] checklistRoles;
    private final String[] ncrRaiseRoles;
    private final String[] correctiveActionRoles;
    private final String[] qualitySignOffRoles;
    private final String[] safetySignOffRoles;

    public InspectionSecurityService(
            OrganizationSecurityService orgSecurity,
            NcrRepository ncrRepo,
            @Value("${echno.security.inspection.read-roles:"
                    + "system-admin,project-manager,qa-engineer,safety-officer,site-engineer}")
            String[] readRoles,
            @Value("${echno.security.inspection.manage-roles:"
                    + "system-admin,project-manager,qa-engineer,safety-officer}")
            String[] manageRoles,
            @Value("${echno.security.inspection.checklist-roles:system-admin,qa-engineer}")
            String[] checklistRoles,
            @Value("${echno.security.inspection.ncr-raise-roles:"
                    + "system-admin,qa-engineer,safety-officer}")
            String[] ncrRaiseRoles,
            @Value("${echno.security.inspection.corrective-action-roles:system-admin,site-engineer}")
            String[] correctiveActionRoles,
            @Value("${echno.security.inspection.quality-signoff-roles:system-admin,qa-engineer}")
            String[] qualitySignOffRoles,
            @Value("${echno.security.inspection.safety-signoff-roles:system-admin,safety-officer}")
            String[] safetySignOffRoles) {
        this.orgSecurity = orgSecurity;
        this.ncrRepo = ncrRepo;
        this.readRoles = readRoles;
        this.manageRoles = manageRoles;
        this.checklistRoles = checklistRoles;
        this.ncrRaiseRoles = ncrRaiseRoles;
        this.correctiveActionRoles = correctiveActionRoles;
        this.qualitySignOffRoles = qualitySignOffRoles;
        this.safetySignOffRoles = safetySignOffRoles;
    }

    /** Read inspections, checklist templates and non-conformance reports. */
    public boolean canRead() {
        return orgSecurity.hasAnyOrgRoleForCurrentTenant(readRoles);
    }

    /** Schedule an inspection and record what it found. */
    public boolean canManageInspections() {
        return orgSecurity.hasAnyOrgRoleForCurrentTenant(manageRoles);
    }

    /** Define the criteria work is judged against. */
    public boolean canDefineChecklists() {
        return orgSecurity.hasAnyOrgRoleForCurrentTenant(checklistRoles);
    }

    /** Raise a non-conformance and hand it to a site engineer. */
    public boolean canRaiseNcrs() {
        return orgSecurity.hasAnyOrgRoleForCurrentTenant(ncrRaiseRoles);
    }

    /** Report the corrective work done and the non-conformance ready for re-inspection. */
    public boolean canReportCorrectiveAction() {
        return orgSecurity.hasAnyOrgRoleForCurrentTenant(correctiveActionRoles);
    }

    /**
     * Whether the caller may verify, reject, reopen or close this particular
     * non-conformance.
     *
     * <p>The discipline has to match: a safety officer cannot close a quality
     * non-conformance and a QA engineer cannot close a safety one. Both are
     * sign-offs on work outside the signer's competence, and the report is the
     * record that somebody qualified accepted it. The check is per-report rather
     * than per-endpoint because the discipline is a property of the report, not of
     * the request.
     *
     * <p>A report that does not exist in this tenant is allowed through, so the
     * caller gets the service's 404 rather than a 403 that would read as "it exists
     * and you may not touch it".
     *
     * @param ncrId Id of the report being signed off.
     * @return true when the caller holds the sign-off role for that report's discipline.
     */
    @Transactional(readOnly = true)
    public boolean canSignOffNcr(UUID ncrId) {
        return ncrRepo.findByIdScoped(ncrId)
                .map(Ncr::getType)
                .map(type -> orgSecurity.hasAnyOrgRoleForCurrentTenant(
                        type == NcrType.SAFETY ? safetySignOffRoles : qualitySignOffRoles))
                .orElse(true);
    }
}
