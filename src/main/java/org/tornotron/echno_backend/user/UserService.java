package org.tornotron.echno_backend.user;

import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.user.dto.UserCreationDto;
import org.tornotron.echno_backend.user.dto.UserDto;
import org.tornotron.echno_backend.user.enums.UserRole;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    private UserDto convertToDto(User user) {
        UserDto dto = new UserDto();
        dto.setId(user.getId());
        dto.setName(user.getName());
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


    public UserDto addUser(UserCreationDto userCreationDto) {
        User user = new User();
        user.setName(userCreationDto.getName());
        user.setBloodGroup(userCreationDto.getBloodGroup());
        user.setEmail(userCreationDto.getEmail());
        user.setPhone(userCreationDto.getPhone());
        user.setDateOfBirth(userCreationDto.getDateOfBirth());
        user.setQualification(userCreationDto.getQualification());
        user.setSkills(userCreationDto.getSkills());
        user.setExperience(userCreationDto.getExperience());
        user.setCvUrl(userCreationDto.getCvUrl());
        user.setEmergencyContact(userCreationDto.getEmergencyContact());
        user.setRole(UserRole.valueOf(userCreationDto.getRole()));
        user.setProfilePictureUrl(userCreationDto.getProfilePictureUrl());
        return convertToDto(userRepository.save(user));
    }

}
