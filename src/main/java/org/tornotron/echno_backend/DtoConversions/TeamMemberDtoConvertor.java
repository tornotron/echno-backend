package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.teamMember.TeamMember;
import org.tornotron.echno_backend.teamMember.dto.TeamMemberDto;

@Component
public class TeamMemberDtoConvertor {

   public static TeamMemberDto convertTeamMemberToDTO(TeamMember teamMember) {
        TeamMemberDto dto = new TeamMemberDto();
        dto.setId(teamMember.getId());
        dto.setMemberName(teamMember.getMemberName());
        dto.setMemberEmail(teamMember.getMemberEmail());
        dto.setMemberPhone(teamMember.getMemberPhone());
        dto.setMemberRole(teamMember.getMemberRole());
        dto.setMemberImage(teamMember.getMemberImage());
        return dto;
    }

}
