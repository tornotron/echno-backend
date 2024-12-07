package org.tornotron.echno_backend.site.dto;

import lombok.Data;

import java.util.List;

@Data
public class TeamDto {
    private Long id;
    private String teamName;
    private List<TeamMemberDto> teamMembers;
}
