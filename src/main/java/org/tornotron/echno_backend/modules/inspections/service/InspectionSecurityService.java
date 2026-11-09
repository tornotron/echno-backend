package org.tornotron.echno_backend.modules.inspections.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.modules.inspections.NcrType;
import org.tornotron.echno_backend.modules.inspections.domain.Ncr;
import org.tornotron.echno_backend.modules.inspections.repositories.NcrRepository;
import org.tornotron.echno_backend.modules.inspections.repositories.ReinspectionRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * The parts of the inspection policy a plain {@code @access.can} guard cannot express.
 *
 * <p>Most inspection endpoints ask the access matrix directly: {@code inspections} for inspections,
 * observations and the compliance and dataset jobs, {@code inspection-checklists} for checklist
 * templates, element types and trades, and {@code ncrs} for non-conformance reports. Two things are
 * left here.
 *
 * <ul>
 *   <li>Signing off an NCR, and recording a reinspection outcome, needs the sign-off action of the
 *       report's own discipline: {@code ncrs:sign-off-safety} for a safety report and
 *       {@code ncrs:sign-off-quality} for any other. A safety officer cannot close a quality report
 *       and a QA engineer cannot close a safety one, since both would be a sign-off on work outside
 *       the signer's competence. The guard asks the matrix for both actions and passes the answers
 *       here, so the literals stay in the guard where the catalog reads them, and this bean picks the
 *       one the report's discipline calls for.</li>
 *   <li>Posting a machine observation stays on the role check,
 *       {@code echno.security.inspection.observation-intake-roles} (default
 *       {@code system-admin,observation-producer}). The producer is a Keycloak service account with
 *       no employee row, whose token carries the role groups; the matrix resolves a caller through
 *       their employee row, so it would refuse every machine login. It moves when machine logins get
 *       their own credential.</li>
 * </ul>
 */
@Service("inspectionSecurity")
public class InspectionSecurityService {

    private final OrganizationSecurityService orgSecurity;
    private final NcrRepository ncrRepo;
    private final ReinspectionRepository reinspectionRepo;
    private final String[] observationIntakeRoles;

    public InspectionSecurityService(
            OrganizationSecurityService orgSecurity,
            NcrRepository ncrRepo,
            ReinspectionRepository reinspectionRepo,
            @Value("${echno.security.inspection.observation-intake-roles:system-admin,observation-producer}")
            String[] observationIntakeRoles) {
        this.orgSecurity = orgSecurity;
        this.ncrRepo = ncrRepo;
        this.reinspectionRepo = reinspectionRepo;
        this.observationIntakeRoles = observationIntakeRoles;
    }

    /**
     * Post a machine observation. The producer role is the org-scoped role a fleet's or
     * integration's Keycloak service account is placed in, exactly as a person is placed in
     * {@code qa-engineer}: the service-account user joins the organisation group and the role
     * subgroup, its client-credentials token carries them in {@code groups}, and the JWT converter
     * turns them into the same authorities.
     */
    public boolean canIntakeObservations() {
        return orgSecurity.hasAnyOrgRoleForCurrentTenant(observationIntakeRoles);
    }

    /**
     * Whether the caller may verify, reject, reopen or close this particular non-conformance, or
     * schedule its reinspection.
     *
     * <p>A report that does not exist in this tenant is allowed through, so the caller gets the
     * service's 404 rather than a 403 that would read as "it exists and you may not touch it".
     *
     * @param ncrId Id of the report being signed off.
     * @param qualitySignOff Whether the caller holds {@code ncrs:sign-off-quality}.
     * @param safetySignOff Whether the caller holds {@code ncrs:sign-off-safety}.
     * @return the answer for that report's discipline.
     */
    @Transactional(readOnly = true)
    public boolean maySignOffNcr(UUID ncrId, boolean qualitySignOff, boolean safetySignOff) {
        return disciplineOf(ncrId)
                .map(type -> type == NcrType.SAFETY ? safetySignOff : qualitySignOff)
                .orElse(true);
    }

    /**
     * Recording a reinspection outcome is the same authority as signing off the NCR it answers. A
     * defect-only reinspection has no NCR to take a discipline from and falls to the quality
     * sign-off. Unknown ids pass through so the service can answer 404 rather than 403.
     */
    @Transactional(readOnly = true)
    public boolean mayRecordReinspectionOutcome(UUID reinspectionId, boolean qualitySignOff, boolean safetySignOff) {
        return reinspectionRepo.findByIdScoped(reinspectionId)
                .map(r -> r.getNcrId() != null
                        ? maySignOffNcr(r.getNcrId(), qualitySignOff, safetySignOff)
                        : qualitySignOff)
                .orElse(true);
    }

    private Optional<NcrType> disciplineOf(UUID ncrId) {
        return ncrRepo.findByIdScoped(ncrId).map(Ncr::getType);
    }
}
