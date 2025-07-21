package org.tornotron.echno_backend.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class TaskCreationDto {
    @NotBlank(message = "title is required")
    @Size(min = 3, max = 50, message = "title must be between 3 and 50 characters")
    private String title;

    private LocalDateTime startDate;

    private LocalDateTime endDate;

    @NotNull(message = "creatorId is required(type: Long)")
    private Long creatorId;

    @NotNull(message = "projectId is required(type: Long)")
    private Long projectId;

    @NotNull(message = "assigneeIds is required(type: List<Long>)")
    private List<Long> assigneeIds;

    @NotBlank(message = "category is required")
    private String category;

    @NotNull(message = "progress is required(type: Double)")
    private Double progress;

    @NotNull(message = "tags is required(type: List<String>)")
    private List<String> tags;

    @NotBlank(message = "status is required")
    private String status;
}
