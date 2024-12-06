package org.tornotron.echno_backend.site.dto;

import lombok.Data;
import org.tornotron.echno_backend.site.enums.ProjectCreationStatus;

import java.util.List;

@Data
public class ProjectDto {
    private Long id;
    private String projectName;
    private String projectAddress;
    private ProjectCreationStatus status;
    private List<TeamDto> teams;
}
