package org.tornotron.echno_backend.category.dto;

import lombok.Data;

import java.util.Map;

@Data
public class CategoryPatchDto {
    private Long id;
    private Map<String, Object> updates;
}
