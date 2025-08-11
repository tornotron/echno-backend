package org.tornotron.echno_backend.task.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Data
public class TaskDto {
    private Long id;
    private String title;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private Long creatorId;
    private Long projectId;
    private Set<Long> assigneeIds;
    private Long categoryId;
    private Double progress;
    private List<String> tags;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String status;
}
