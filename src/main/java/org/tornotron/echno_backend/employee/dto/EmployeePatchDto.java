package org.tornotron.echno_backend.employee.dto;

import lombok.Data;

import java.util.Map;

@Data
public class EmployeePatchDto {
    private Long id;
    private Map<String,Object> updates;
}
