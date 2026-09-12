package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.modules.inspections.DefectSeverity;
import org.tornotron.echno_backend.modules.inspections.DefectStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "A defect identified during an inspection, as returned by the API.")
public record InspectionDefectDto(
        UUID id,
        @Schema(description = "Grouping the defect belongs to. Null where none was recorded.",
                nullable = true)
        String category,
        String description,
        @Schema(description = "How serious the defect is. Null where none was recorded: the "
                + "migration that promoted the free-text column to the enum cleared blank values "
                + "to null rather than guessing a severity.", nullable = true)
        DefectSeverity severity,
        @Schema(description = "Where on site the defect was found. Null where none was recorded.",
                nullable = true)
        String location,
        List<String> photos,
        String correctiveAction,
        @Schema(description = "Who is accountable for putting the defect right. Null where none "
                + "was recorded.", nullable = true)
        String responsibleParty,
        @Schema(description = "Date the corrective action is due. Null where none was agreed.",
                nullable = true)
        LocalDate targetDate,
        @Schema(description = "Where the defect stands. The service substitutes OPEN wherever a "
                + "payload omits it, but the column permits null, so a row loaded outside the "
                + "application can carry none.", nullable = true)
        DefectStatus status,
        @Schema(description = "Date the defect was resolved. Null until it is.", nullable = true)
        LocalDate resolvedDate
) {}
