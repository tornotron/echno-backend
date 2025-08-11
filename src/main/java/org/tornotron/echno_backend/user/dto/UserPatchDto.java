package org.tornotron.echno_backend.user.dto;

import lombok.Data;

import java.util.Map;

@Data
public class UserPatchDto {
    private Long id;
    private Map<String ,Object> updates;
}
