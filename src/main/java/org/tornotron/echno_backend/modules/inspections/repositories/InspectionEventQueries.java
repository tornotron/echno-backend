package org.tornotron.echno_backend.modules.inspections.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionEvent;

/** The read side of the event log, implemented with the criteria API so it stays read-only. */
public interface InspectionEventQueries {

    /**
     * Events matching the filter within the given organization, oldest first. The page's own
     * sort is ignored: a timeline has one order.
     */
    Page<InspectionEvent> search(Long organizationId, InspectionEventFilter filter, Pageable pageable);
}
