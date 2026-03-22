package org.tornotron.echno_backend.indent.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class IndentUpdateDto {
    private String indentNumber;
    private Long createdByemployeeId;
    private Long projectId;
    private String status;
    private LocalDateTime expectedOn;
    private String remarks;
}
