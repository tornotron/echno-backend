package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.dto.ProjectDto;
import org.tornotron.echno_backend.teamMember.TeamMember;
import org.tornotron.echno_backend.teamMember.dto.TeamMemberDto;

import java.util.stream.Collectors;

@Component
public class ProjectDtoConvertor {

    private static TeamMemberDto convertTeamMemberToTeamMemberDTO(TeamMember teamMember) {
        TeamMemberDto teamMemberDto = new TeamMemberDto();
        teamMemberDto.setId(teamMember.getId());
        teamMemberDto.setMemberName(teamMember.getMemberName());
        teamMemberDto.setMemberEmail(teamMember.getMemberEmail());
        teamMemberDto.setMemberPhone(teamMember.getMemberPhone());
        teamMemberDto.setMemberRole(teamMember.getMemberRole());
        teamMemberDto.setMemberImage(teamMember.getMemberImage());
        return teamMemberDto;
    }

    public static ProjectDto convertProjectToDto(Project project) {
        ProjectDto dto = new ProjectDto();
        dto.setId(project.getId());
        dto.setProjectName(project.getProjectName());
        dto.setProjectAddress(project.getProjectAddress());
        dto.setStatus(project.getStatus());
        dto.setCreatedAt(project.getCreatedAt());
        dto.setTeamMembers(project.getTeamMembers().stream()
                .map(ProjectDtoConvertor::convertTeamMemberToTeamMemberDTO)
                .collect(Collectors.toList()));
        return dto;
    }

}
