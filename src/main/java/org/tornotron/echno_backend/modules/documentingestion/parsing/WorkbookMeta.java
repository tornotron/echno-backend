package org.tornotron.echno_backend.modules.documentingestion.parsing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** The parts of {@code meta.json} a reader needs: the file type, the sheets and the worker's warnings. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkbookMeta(String fileType, String sourceSha256, List<Sheet> sheets, List<String> warnings) {

    public WorkbookMeta {
        sheets = sheets == null ? List.of() : sheets;
        warnings = warnings == null ? List.of() : warnings;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sheet(int index, String name, boolean hidden, Integer rowCount, Integer columnCount) {
    }
}
