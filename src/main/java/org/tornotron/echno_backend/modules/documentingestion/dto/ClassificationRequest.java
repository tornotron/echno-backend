package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocClass;

@Schema(description = "The class and page segments a person confirms for one document. Replaces what was proposed.")
public record ClassificationRequest(
        @Schema(description = "The document's class") @NotNull DocClass docClass,
        @Schema(description = "Page ranges of a PDF, in order and not overlapping. Empty means one segment of the whole "
                + "file in the document's class; ignored for a workbook, whose sheets are read through the BOQ "
                + "mapping, and for a DXF or DWG, which is read as one drawing file",
                nullable = true)
        @Size(max = 200) List<@Valid @NotNull Range> segments) {

    @Schema(description = "One page range and its class.")
    public record Range(@NotNull DocClass docClass,
                        @NotNull @Min(1) @Max(2000) Integer pageFrom,
                        @NotNull @Min(1) @Max(2000) Integer pageTo) {
    }
}
