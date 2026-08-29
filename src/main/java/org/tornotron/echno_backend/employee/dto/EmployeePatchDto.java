package org.tornotron.echno_backend.employee.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Schema(description = "A single employee's partial update within a batch: the employee id and the map "
        + "of fields to change on them.")
@Data
public class EmployeePatchDto {
    @Schema(description = "Id of the employee to update.", example = "7")
    private Long id;

    @Schema(implementation = EmployeeUpdateFieldsDto.class)
    private Map<String, Object> updates;
}
