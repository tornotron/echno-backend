package org.tornotron.echno_backend.common.entity;

import lombok.Data;

@Data
public class AttachmentDto {
    private Long id;
    private String url;
    private String entityType;
    private String contentType;
    private Long fileSize;
    private String fileName;
    private String createdAt;
}
