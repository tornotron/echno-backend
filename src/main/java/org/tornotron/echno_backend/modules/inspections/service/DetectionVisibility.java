package org.tornotron.echno_backend.modules.inspections.service;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.modules.inspections.repositories.ObservationRepository;

/**
 * Who may see a model's detections: decision A4 of the computer vision design note, which is still
 * open. Its default is that the pilot organization's own users do not, until the validation report
 * exists, and Fereydon's reviewers do.
 *
 * <p>{@code echno.security.inspection.detection-read-roles} holds the org-role tokens that see them,
 * default {@code detection-reviewer}: a role nobody holds until a reviewer is placed in that subgroup
 * of the organization, and one a client's {@code system-admin} does not hold by being one. If the
 * answer to A4 is yes, setting the property to the inspection read roles is the whole change.
 *
 * <p>A detection is a machine finding that came through intake from a model, or that carries a
 * producer's region; see {@code ObservationSpecifications.isDetection}. Rows a person recorded and the
 * compliance model's suggestions are not detections and are not affected.
 */
@Service("detectionVisibility")
public class DetectionVisibility {

    private final OrganizationSecurityService orgSecurity;
    private final ObservationRepository observations;
    private final String[] roles;

    public DetectionVisibility(OrganizationSecurityService orgSecurity, ObservationRepository observations,
                               @Value("${echno.security.inspection.detection-read-roles:detection-reviewer}")
                               String[] roles) {
        this.orgSecurity = orgSecurity;
        this.observations = observations;
        this.roles = roles;
    }

    /** Whether the caller sees detections at all in the current tenant. */
    public boolean callerMaySeeDetections() {
        return orgSecurity.hasAnyOrgRoleForCurrentTenant(roles);
    }

    /**
     * Whether the caller may see this observation. An id the tenant does not hold passes, so the
     * caller gets the service's 404 and not a 403 that says it exists.
     */
    @Transactional(readOnly = true)
    public boolean maySee(UUID observationId) {
        return callerMaySeeDetections() || observations.countDetectionScoped(observationId) == 0;
    }
}
