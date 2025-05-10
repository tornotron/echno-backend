package org.tornotron.echno_backend.organization.dto;

import lombok.Data;
import org.tornotron.echno_backend.project.dto.ProjectDto;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrganizationDto {
    private Long id;
    private String organizationName;
    private String organizationAddress;
    private LocalDateTime createdAt;
    private List<ProjectDtoForOrg> projects;
}
