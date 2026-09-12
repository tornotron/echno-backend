package org.tornotron.echno_backend.modules.bim.importer;

import java.util.Map;
import org.tornotron.echno_backend.modules.bim.domain.BimModel;
import org.tornotron.echno_backend.modules.bim.domain.BimModelVersion;

/**
 * A step that runs inside the ingestion transaction after the element table is up to date,
 * with the worker's {@code structure.json} and {@code model-meta.json} in hand. The hierarchy
 * proposal is one; anything that throws fails the version.
 */
public interface BimIngestionListener {

    void afterElementsIngested(BimModel model, BimModelVersion version,
                               Map<String, Object> structure, Map<String, Object> meta);
}
