package org.tornotron.echno_backend.site.dto;

import lombok.Data;

@Data
public class TeamMemberDto {
    private Long id;
    private String memberName;
    private String memberEmail;
}
