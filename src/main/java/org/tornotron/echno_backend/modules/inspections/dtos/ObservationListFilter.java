package org.tornotron.echno_backend.modules.inspections.dtos;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.tornotron.echno_backend.modules.inspections.ObservationReviewStatus;
import org.tornotron.echno_backend.modules.inspections.api.ObservationSource;

/**
 * The narrowing of an observation list. Every field but {@code includeDetections} is optional, and they
 * narrow together. {@code hasRegions} and {@code attachmentId} look at active regions only.
 *
 * @param includeDetections whether a model's detections are listed; see {@code DetectionVisibility}
 */
public record ObservationListFilter(Long projectId,
                                    ObservationReviewStatus reviewStatus,
                                    ObservationSource source,
                                    UUID inspectionId,
                                    UUID spatialNodeId,
                                    LocalDateTime from,
                                    LocalDateTime to,
                                    boolean includeDetections,
                                    String modelName,
                                    String modelVersion,
                                    List<String> categories,
                                    BigDecimal minConfidence,
                                    BigDecimal maxConfidence,
                                    Boolean hasRegions,
                                    Long attachmentId) {

    /** Without the model filters. */
    public ObservationListFilter(Long projectId,
                                 ObservationReviewStatus reviewStatus,
                                 ObservationSource source,
                                 UUID inspectionId,
                                 UUID spatialNodeId,
                                 LocalDateTime from,
                                 LocalDateTime to,
                                 boolean includeDetections) {
        this(projectId, reviewStatus, source, inspectionId, spatialNodeId, from, to, includeDetections, null, null, null,
                null, null, null, null);
    }
}
