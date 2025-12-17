package org.tornotron.echno_backend.material.dto;

import lombok.Data;

@Data
public class MaterialDto {

    private Long id;
    private String sku;
    private String materialName;
    private String unit;
}
