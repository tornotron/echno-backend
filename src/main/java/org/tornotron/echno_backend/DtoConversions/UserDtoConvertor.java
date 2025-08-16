package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.dto.UserDto;

@Component
public class UserDtoConvertor {

    public static UserDto convertUserToDto(User user) {
        UserDto dto = new UserDto();
        dto.setId(user.getId());
        dto.setName(user.getName());
        dto.setGender(user.getGender());
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
        return dto;
    }
}
