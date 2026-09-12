package org.tornotron.echno_backend.modules.bim.importer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/** One line of {@code elements.jsonl}, as the import contract defines it. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BimElementLine(
        String globalId,
        String ifcType,
        String name,
        String storeyGlobalId,
        String spaceGlobalId,
        Map<String, Object> bbox,
        Map<String, Object> properties
) {}
