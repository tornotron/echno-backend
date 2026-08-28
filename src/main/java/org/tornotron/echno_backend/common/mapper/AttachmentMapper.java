package org.tornotron.echno_backend.common.mapper;

import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.service.FileStorageService;

import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Maps {@link Attachment} to its DTO, resolving a short-lived presigned download URL
 * from {@link FileStorageService}. Shared by every mapper that exposes attachments, so
 * the URL-signing logic lives in one place instead of being copied into each converter.
 *
 * Abstract class (not interface) so the service can be field-injected and an
 * {@code @AfterMapping} hook can fill the signed URL after the plain fields are copied.
 */
@Mapper(componentModel = "spring")
public abstract class AttachmentMapper {

    @Autowired
    protected FileStorageService fileStorageService;

    @Mapping(target = "url", ignore = true) // filled from a presigned URL in fillUrl
    @Mapping(source = "originalFilename", target = "fileName")
    @Mapping(target = "createdAt", expression = "java(attachment.getCreatedAt().toString())")
    @Mapping(target = "updatedAt", expression = "java(attachment.getUpdatedAt().toString())")
    @Mapping(target = "expired", ignore = true) // derived from expiresOn in fillExpiry
    @Mapping(target = "daysUntilExpiry", ignore = true) // derived from expiresOn in fillExpiry
    public abstract AttachmentDto toDto(Attachment attachment);

    @AfterMapping
    protected void fillUrl(Attachment attachment, @MappingTarget AttachmentDto dto) {
        dto.setUrl(fileStorageService.generateDownloadUrl(attachment.getStorageKey(), Duration.ofHours(1)));
        fillExpiry(attachment.getExpiresOn(), dto);
    }

    /**
     * Fills the two derived expiry fields. They are computed on read rather than stored, so a
     * document cannot sit in the database marked valid the morning after it lapsed.
     *
     * @param expiresOn The document's expiry date, or null where it has none.
     * @param dto The DTO being filled.
     */
    public static void fillExpiry(LocalDate expiresOn, AttachmentDto dto) {
        if (expiresOn == null) {
            return;
        }
        LocalDate today = LocalDate.now();
        dto.setExpired(expiresOn.isBefore(today));
        dto.setDaysUntilExpiry(ChronoUnit.DAYS.between(today, expiresOn));
    }
}
