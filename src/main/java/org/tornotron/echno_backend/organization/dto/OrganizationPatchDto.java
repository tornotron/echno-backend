package org.tornotron.echno_backend.organization.dto;

import lombok.Data;

import java.util.Map;

@Data
public class OrganizationPatchDto {
    private Long id;
    private Map<String ,Object> updates;
}
