package org.tornotron.echno_backend.modules.sitenotes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

@Schema(description = "Changes a site note's date and text. The project and the author never change.")
public record UpdateSiteNoteRequest(
        @NotNull LocalDate noteDate,
        @NotBlank @Size(max = 2000) String note
) {}
