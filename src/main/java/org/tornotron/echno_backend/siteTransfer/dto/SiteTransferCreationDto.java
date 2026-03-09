package org.tornotron.echno_backend.siteTransfer.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class SiteTransferCreationDto {

    @NotBlank(message = "transfer number is required")
    @Size(min = 1, max = 50, message = "transfer number must be between 1 and 50 characters")
    private String transferNumber;

    @NotNull(message = "issue date is required")
    private LocalDateTime issueDate;

    @NotNull(message = "sending person employee id is required")
    private Long sendingPerson;

    @NotBlank(message = "receiving site is required")
    @Size(min = 1, max = 100, message = "receiving site must be between 1 and 100 characters")
    private String receivingSite;

    @NotBlank(message = "status is required")
    private String status;

    @NotEmpty(message = "items list cannot be empty")
    @Valid
    private List<SiteTransferItemDto> items;
}
