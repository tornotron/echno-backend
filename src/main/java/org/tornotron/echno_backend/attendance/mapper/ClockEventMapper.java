package org.tornotron.echno_backend.attendance.mapper;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.attendance.ClockEvent;
import org.tornotron.echno_backend.attendance.dto.ClockEventDto;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.service.FileStorageService;

import java.time.Duration;
import java.util.stream.Collectors;

@Component
public class ClockEventMapper {

    public static AttachmentDto convertAttachmentToDto(Attachment attachment, FileStorageService fileStorageService) {
        AttachmentDto dto = new AttachmentDto();
        dto.setId(attachment.getId());
        dto.setUrl(fileStorageService.generateDownloadUrl(attachment.getStorageKey(), Duration.ofHours(1)));
        dto.setEntityType(attachment.getEntityType());
        dto.setContentType(attachment.getContentType());
        dto.setFileSize(attachment.getFileSize());
        dto.setFileName(attachment.getOriginalFilename());
        dto.setCreatedAt(attachment.getCreatedAt().toString());
        dto.setUpdatedAt(attachment.getUpdatedAt().toString());
        return dto;
    }

    public static ClockEventDto toDto(ClockEvent entity,FileStorageService fileStorageService) {
        if (entity == null) return null;
        return ClockEventDto.builder()
                .id(entity.getId())
                .eventType(entity.getEventType())
                .eventTimestamp(entity.getEventTimestamp())
                .latitude(entity.getLatitude())
                .longitude(entity.getLongitude())
                .gpsAccuracy(entity.getGpsAccuracy())
                .projectId(entity.getProjectId())
                .projectName(entity.getProjectName())
                .devicePlatform(entity.getDevicePlatform())
                .isWithinGeofence(entity.getIsWithinGeofence())
                .distanceFromProject(entity.getDistanceFromProject())
                .remarks(entity.getRemarks())
                .verifiedBy(entity.getVerifiedBy())
                .verifiedAt(entity.getVerifiedAt())
                .isRegularized(entity.getIsRegularized())
                .regularizationReason(entity.getRegularizationReason())
                .attachments(entity.getAttachments().stream()
                        .map(attachment -> convertAttachmentToDto(attachment, fileStorageService))
                        .collect(Collectors.toList()))
                .build();
    }
}
