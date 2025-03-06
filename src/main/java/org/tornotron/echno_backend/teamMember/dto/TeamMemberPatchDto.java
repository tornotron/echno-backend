package org.tornotron.echno_backend.teamMember.dto;

import lombok.Data;

import java.util.Map;

@Data
public class TeamMemberPatchDto {
    private Long id;
    private Map<String,Object> updates;
}
