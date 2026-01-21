package org.tornotron.echno_backend.project.dto;

public record StoredFile(
        String key,
        String url,
        String contentType,
        Long size) {
}