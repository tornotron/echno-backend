package org.tornotron.echno_backend.project.dto;

import lombok.Data;
import org.tornotron.echno_backend.project.enums.ProjectCreationStatus;
import org.tornotron.echno_backend.project.enums.ProjectType;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ProjectSimpleDto {
    private Long id;
    private String projectName;
    private String projectAddress;
    private LocalDateTime createdAt;
    private ProjectCreationStatus status;
    private ProjectType projectType;
    private Float projectLatitude;
    private Float projectLongitude;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private Double progress;
}
