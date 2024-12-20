package org.tornotron.echno_backend.project.dto;

import lombok.Data;
import org.tornotron.echno_backend.project.enums.ProjectCreationStatus;
import org.tornotron.echno_backend.teamMember.dto.TeamMemberDto;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ProjectDto {
    private Long id;
    private String projectName;
    private String projectAddress;
    private LocalDateTime createdAt;
    private ProjectCreationStatus status;
    private List<TeamMemberDto> teamMembers;
}
