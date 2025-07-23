package org.tornotron.echno_backend.task.dto;

import lombok.Data;

import java.util.Map;


@Data
public class TaskPatchDto {
    private Long id;
    private Map<String, Object> updates;
}
