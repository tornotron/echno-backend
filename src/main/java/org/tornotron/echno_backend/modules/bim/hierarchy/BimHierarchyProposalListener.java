package org.tornotron.echno_backend.modules.bim.hierarchy;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.modules.bim.domain.BimModel;
import org.tornotron.echno_backend.modules.bim.domain.BimModelVersion;
import org.tornotron.echno_backend.modules.bim.importer.BimIngestionListener;

/** Plugs the proposal into the ingestion: built after the elements land, inside the same transaction. */
@Component
@RequiredArgsConstructor
public class BimHierarchyProposalListener implements BimIngestionListener {

    private final BimHierarchyService hierarchyService;

    @Override
    public void afterElementsIngested(BimModel model, BimModelVersion version,
                                      Map<String, Object> structure, Map<String, Object> meta) {
        hierarchyService.propose(model, version, structure);
    }
}
