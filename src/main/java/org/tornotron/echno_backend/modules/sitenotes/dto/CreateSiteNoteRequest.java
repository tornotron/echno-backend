package org.tornotron.echno_backend.modules.sitenotes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

@Schema(description = "Adds a site note for a project of the current tenant. The author is the "
        + "caller, not a field of this request: naming someone else as the author would let any "
        + "manager file a note in a colleague's name.")
public record CreateSiteNoteRequest(
        @NotNull Long projectId,
        @NotNull LocalDate noteDate,
        @NotBlank @Size(max = 2000) String note
) {}
