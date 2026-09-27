package org.tornotron.echno_backend.modules.workprogress.service;

import java.util.Collection;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.workprogress.repository.ProgressInspectionRepository;
import org.tornotron.echno_backend.wbs.WbsActivityRecords;

/**
 * Tells the schedule that an activity with progress inspections is part of the record and so
 * cannot be deleted. The inspection history is what a later claim and certificate rest on.
 */
@Component
public class ProgressInspectionActivityRecords implements WbsActivityRecords {

    private final ProgressInspectionRepository inspections;

    public ProgressInspectionActivityRecords(ProgressInspectionRepository inspections) {
        this.inspections = inspections;
    }

    @Override
    public String recordsHeldAgainst(Collection<Long> elementIds) {
        if (elementIds.isEmpty()) {
            return null;
        }
        return inspections.existsForElements(elementIds, TenantContext.getCurrentOrgId()) ? "progress inspections" : null;
    }
}
