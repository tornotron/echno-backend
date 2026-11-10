package org.tornotron.echno_backend.modules.inspections.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.modules.access.api.CallerAccess;
import org.tornotron.echno_backend.modules.access.api.ChecksAccess;
import org.tornotron.echno_backend.modules.inspections.repositories.ObservationRepository;

/**
 * Who may see a model's detections: decision A4 of the computer vision design note, which is still
 * open. Its default is that the pilot organization's own users do not, until the validation report
 * exists, and Fereydon's reviewers do.
 *
 * <p>Seeing them is {@code inspections:read-detections}, which only the detection reviewer's starting
 * role holds: a role nobody holds until a reviewer is given it, and one a client's system admin does
 * not hold by being one. Before the matrix this was {@code echno.security.inspection.detection-read-roles},
 * default {@code detection-reviewer}, which no environment overrode. If the answer to A4 is yes, giving
 * the inspection readers' roles that grant is the whole change.
 *
 * <p>A detection is a machine finding that came through intake from a model, or that carries a
 * producer's region; see {@code ObservationSpecifications.isDetection}. Rows a person recorded and the
 * compliance model's suggestions are not detections and are not affected.
 */
@Service("detectionVisibility")
@ChecksAccess(DetectionVisibility.AREA + ":" + DetectionVisibility.READ_DETECTIONS)
public class DetectionVisibility {

    /** Seeing a model's detections: the detection reviewer's, from its starting grants. */
    static final String AREA = "inspections";
    static final String READ_DETECTIONS = "read-detections";

    private final CallerAccess access;
    private final ObservationRepository observations;

    public DetectionVisibility(CallerAccess access, ObservationRepository observations) {
        this.access = access;
        this.observations = observations;
    }

    /** Whether the caller sees detections at all in the current tenant. */
    public boolean callerMaySeeDetections() {
        return access.can(AREA, READ_DETECTIONS);
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
