package org.tornotron.echno_backend.task.dto;

import lombok.Data;

@Data
public class TaskCreationDto {
    private String taskName;
    private String categories;
    private Integer progress;
}
