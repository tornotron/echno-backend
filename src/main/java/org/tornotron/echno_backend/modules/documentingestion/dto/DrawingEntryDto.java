package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.tornotron.echno_backend.modules.documentingestion.domain.DrawingEntry;

/**
 * One row of the drawing register. Provenance for each field is at
 * {@code GET /provenance?targetType=DRAWING&targetId=<id>}.
 */
@Schema(description = "One drawing number at one revision, with its title-block values as printed. The rows of a "
        + "number form a revision chain through supersedesId and supersededById; the newest read revision is current. "
        + "Only the title block is read: nothing else on the drawing.")
public record DrawingEntryDto(
        UUID id,
        UUID packageId,
        Long projectId,
        @Schema(example = "RCB-AR-101")
        String drawingNumber,
        @Schema(nullable = true, example = "B")
        String revision,
        @Schema(nullable = true, example = "GROUND FLOOR PLAN")
        String title,
        @Schema(nullable = true, example = "Architectural")
        String discipline,
        @Schema(nullable = true, description = "The revision's date as printed.", example = "12.03.2026")
        String revisionDateText,
        @Schema(nullable = true, description = "The same date when it reads plainly as one.")
        LocalDate revisionDate,
        @Schema(nullable = true, example = "1:100")
        String scale,
        @Schema(nullable = true, description = "The printed revision table, oldest first: revision, date and description.")
        List<Map<String, Object>> revisionHistory,
        @Schema(description = "READ, or NOT_READ for a DWG, which is named by its file only.")
        DrawingEntry.Status status,
        @Schema(nullable = true, example = "Not read, add a PDF or DXF copy.")
        String note,
        @Schema(description = "Whether this is the number's current revision.")
        boolean current,
        @Schema(nullable = true, description = "The older revision this one replaced.")
        UUID supersedesId,
        @Schema(nullable = true, description = "The newer revision that replaced this one.")
        UUID supersededById,
        @Schema(description = "PDF, DXF or DWG.", example = "PDF")
        String sourceFormat,
        UUID documentId,
        @Schema(nullable = true, description = "The PDF page the sheet is on.")
        Integer sheetPage,
        @Schema(nullable = true, description = "The DXF layout the title block is on.")
        String layout,
        @Schema(description = "Whether a title-block crop exists; fetch it from "
                + "GET /documents/{documentId}/title-blocks/{sheetPage}/image.")
        boolean cropAvailable,
        UUID candidateId,
        LocalDateTime updatedAt) {

    public static DrawingEntryDto of(DrawingEntry d) {
        return new DrawingEntryDto(d.getId(), d.getPackageId(), d.getProjectId(), d.getDrawingNumber(), d.getRevision(),
                d.getTitle(), d.getDiscipline(), d.getRevisionDateText(), d.getRevisionDate(), d.getScale(),
                d.getRevisionHistory(), d.getStatus(), d.getNote(), d.isCurrent(), d.getSupersedesId(),
                d.getSupersededById(), d.getSourceFormat(), d.getDocumentId(), d.getSheetPage(), d.getLayout(),
                d.getCropImage() != null, d.getCandidateId(), d.getUpdatedAt());
    }
}
