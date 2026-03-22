package org.tornotron.echno_backend.indent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.tornotron.echno_backend.indentItem.dto.IndentItemCreationDto;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class IndentCreationDto {

    @NotBlank(message = "indentNumber is required(type: String)")
    @Size(min = 1, max = 50, message = "indentNumber must be between 1 and 50 characters")
    private String indentNumber;

    @NotNull(message = "project ID is required")
    private Long projectId;

    @NotNull(message = "createdBy is required(type: Long)")
    private Long createdByEmployeeId;

    @NotBlank(message = "status is required(type: String)")
    private String status;

    private LocalDateTime expectedOn;

    private String remarks;

    @Valid
    private List<IndentItemCreationDto> items;
}
