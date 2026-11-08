package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.tornotron.echno_backend.modules.documentingestion.domain.BoqPreviewRow;
import org.tornotron.echno_backend.modules.documentingestion.domain.BoqSheet;
import org.tornotron.echno_backend.modules.documentingestion.domain.BoqSheetStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.BoqSheetSummary;

@Schema(description = "One sheet of a workbook read as a BOQ: its proposed or confirmed column mapping, its top rows, and what reading it found.")
public record BoqSheetDto(
        UUID id,
        UUID documentId,
        @Schema(description = "Set once a person confirms the mapping; candidates of this sheet carry it", nullable = true) UUID segmentId,
        String sheetName,
        int sheetIndex,
        boolean hidden,
        BoqSheetStatus status,
        @Schema(description = "The header row; data starts below it. 0 means the sheet has no header row", nullable = true) Integer headerRow,
        @Schema(description = "Field to 1-based column. Fields: itemCode, description, unit, quantity, rate, amount") Map<String, Integer> columns,
        @Schema(nullable = true) String codePrefix,
        @Schema(description = "The top rows of the sheet, for the mapping screen") List<BoqPreviewRow> preview,
        int rowCount,
        @Schema(description = "What reading the sheet found, once it is read", nullable = true) BoqSheetSummary summary,
        @Schema(nullable = true) Long confirmedBy,
        @Schema(nullable = true) LocalDateTime confirmedAt,
        @Schema(nullable = true) LocalDateTime extractedAt) {

    public static BoqSheetDto of(BoqSheet s) {
        return new BoqSheetDto(s.getId(), s.getDocumentId(), s.getSegmentId(), s.getSheetName(), s.getSheetIndex(),
                s.isHidden(), s.getStatus(), s.getHeaderRow(), s.getColumns(), s.getCodePrefix(), s.getPreview(),
                s.getRowCount(), s.getSummary(), s.getConfirmedBy(), s.getConfirmedAt(), s.getExtractedAt());
    }
}
