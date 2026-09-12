package org.tornotron.echno_backend.modules.bim.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Confirms the proposal. By default every proposed node is created or matched.")
public record ConfirmBimHierarchyRequest(
        @Schema(description = "False to confirm buildings, floors and zones only and leave elements for later.")
        Boolean includeElements,
        @Schema(description = "Confirm only these element GlobalIds; null or empty for all.")
        List<String> elementGlobalIds
) {
    public boolean elementsWanted() {
        return includeElements == null || includeElements;
    }
}
