package org.tornotron.echno_backend.modules.bim.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BimHierarchyConfirmResult(
        LocalDateTime confirmedAt,
        int nodesCreated,
        int nodesMatched,
        int elementsLinked,
        int elementsSkipped
) {}
