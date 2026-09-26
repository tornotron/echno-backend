package org.tornotron.echno_backend.modules.sitenotes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

@Schema(description = "Adds a site note for a project of the current tenant.")
public record CreateSiteNoteRequest(
        @NotNull Long projectId,
        @NotNull LocalDate noteDate,
        @NotNull Long authorEmployeeId,
        @NotBlank @Size(max = 2000) String note
) {}
