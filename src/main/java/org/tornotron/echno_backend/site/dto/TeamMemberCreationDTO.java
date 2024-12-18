package org.tornotron.echno_backend.site.dto;

import lombok.Data;

@Data
public class TeamMemberCreationDTO {
    private String memberName;
    private String memberEmail;
    private String projectName;
}
