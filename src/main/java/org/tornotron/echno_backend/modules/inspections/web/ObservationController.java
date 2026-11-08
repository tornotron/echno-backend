package org.tornotron.echno_backend.modules.inspections.web;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.modules.inspections.InspectionsModule;
import org.tornotron.echno_backend.modules.inspections.service.DetectionVisibility;
import org.tornotron.echno_backend.modules.inspections.service.ObservationBatchReviewService;
import org.tornotron.echno_backend.modules.inspections.service.ObservationEvidenceService;
import org.tornotron.echno_backend.modules.inspections.service.ObservationService;

/**
 * Serves every endpoint of {@link ObservationControllerWeb} on the tenant API path the mobile client calls
 * (#998). The handlers, and the {@code @PreAuthorize} on each, are inherited rather than
 * copied, so the two paths cannot come to answer differently. When the twin controllers are
 * collapsed (#856), this class goes and ObservationControllerWeb takes both paths.
 */
@RestController
@RequireSubscription(feature = InspectionsModule.FEATURE_KEY)
@Validated
@RequestMapping("/api/v1/inspections/observations")
@Tag(
        name = "Observations (Mobile)",
        description = "The tenant API path of the endpoints under /api/v1/inspections/web/observations, for the mobile client. "
                + "Same handlers, request and response shapes and organization-role guards as the web "
                + "endpoints; send X-Organization-Id to pick the organization."
)
public class ObservationController extends ObservationControllerWeb {

    public ObservationController(ObservationService service, ObservationEvidenceService evidenceService, DetectionVisibility detectionVisibility, ObservationBatchReviewService batchReviewService) {
        super(service, evidenceService, detectionVisibility, batchReviewService);
    }
}
