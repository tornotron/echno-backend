package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.dto.UserDto;

import java.time.Duration;
import java.util.stream.Collectors;

@Component
public class UserDtoConvertor {

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

    public static UserDto convertUserToDto(User user,FileStorageService fileStorageService) {
        UserDto dto = new UserDto();
        dto.setId(user.getId());
        dto.setName(user.getName());
        dto.setGender(user.getGender());
        dto.setAddress(user.getAddress());
        dto.setBloodGroup(user.getBloodGroup());
        dto.setEmail(user.getEmail());
        dto.setPhone(user.getPhone());
        dto.setDateOfBirth(user.getDateOfBirth());
        dto.setQualification(user.getQualification());
        dto.setSkills(user.getSkills());
        dto.setExperience(user.getExperience());
        dto.setCvUrl(user.getCvUrl());
        dto.setEmergencyContact(user.getEmergencyContact());
        dto.setRole(user.getRole());
        dto.setProfilePictureUrl(user.getProfilePictureUrl());
        dto.setCreatedAt(user.getCreatedAt());
        dto.setUpdatedAt(user.getUpdatedAt());
        dto.setDefaultOrganizationId(user.getDefaultOrganizationId());
        dto.setAttachments(user.getAttachments().stream()
                .map(attachment -> convertAttachmentToDto(attachment,fileStorageService))
                .collect(Collectors.toList()));
        return dto;
    }
}
