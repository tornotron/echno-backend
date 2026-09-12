package org.tornotron.echno_backend.modules.inspections.repositories;

import org.springframework.data.repository.Repository;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionEvent;

import java.util.UUID;

/**
 * Append and read, nothing else. Extends the bare {@link Repository} marker rather than
 * {@code JpaRepository} or {@code JpaSpecificationExecutor}, both of which would bring a
 * {@code delete} along; {@code InspectionEventAppendOnlyTest} holds it to that.
 */
@org.springframework.stereotype.Repository
public interface InspectionEventRepository extends Repository<InspectionEvent, UUID>, InspectionEventQueries {

    InspectionEvent save(InspectionEvent event);

    long countByInspectionIdAndOrganization_Id(UUID inspectionId, Long organizationId);
}
