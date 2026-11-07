package org.tornotron.echno_backend.modules.inspections.dtos;

import java.time.LocalDateTime;
import java.util.UUID;
import org.tornotron.echno_backend.modules.inspections.ObservationReviewStatus;
import org.tornotron.echno_backend.modules.inspections.ObservationSource;

/**
 * The narrowing of an observation list. Every field but the last is optional, and they narrow together.
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
                                    boolean includeDetections) {
}
