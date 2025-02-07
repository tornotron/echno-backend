package org.tornotron.echno_backend.task.dto;

import lombok.Data;

@Data
public class TaskDto {
    private String taskName;
    private String categories;
    private byte[] photo;
    private Integer progress;
}
