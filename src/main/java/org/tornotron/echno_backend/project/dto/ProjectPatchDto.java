package org.tornotron.echno_backend.project.dto;

import lombok.Data;

import java.util.Map;

@Data
public class ProjectPatchDto {
    private Long id;
    private Map<String,Object> updates;
}
