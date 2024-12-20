package org.tornotron.echno_backend.teamMember.dto;

import lombok.Data;

@Data
public class TeamMemberCreationDTO {
    private String memberName;
    private String memberEmail;
    private String projectName;
}
