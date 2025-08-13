package org.tornotron.echno_backend.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.user.dto.UserCreationDto;
import org.tornotron.echno_backend.user.dto.UserDto;
import org.tornotron.echno_backend.user.dto.UserPatchDto;
import org.tornotron.echno_backend.user.enums.UserRole;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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

    @Transactional(readOnly = true)
    public Page<UserDto> getAllUsers(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo,pageSize, Sort.by(Sort.Direction.ASC,"id"));
        return userRepository.findAll(pageable)
                .map(this::convertToDto);
    }

    @Transactional(readOnly = true)
    public UserDto getAnUser(Long id) {
        return userRepository.findById(id)
                .map(this::convertToDto)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
    }

    public UserDto partialUpdateAnUser(Map<String, Object> updates, Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
        updates.forEach((key, value) -> {
            switch (key) {
                case "name":
                    user.setName((String) value);
                    break;
                case "bloodGroup":
                    user.setBloodGroup((String) value);
                    break;
                case "email":
                    user.setEmail((String) value);
                    break;
                case "phone":
                    user.setPhone((String) value);
                    break;
                case "dateOfBirth":
                    user.setDateOfBirth((LocalDateTime) value);
                    break;
                case "qualification":
                    user.setQualification((String) value);
                    break;
//                case "skills":
//                    user.setSkills((List<String>) value);
//                    break;
                case "experience":
                    user.setExperience((Integer) value);
                    break;
                case "cvUrl":
                    user.setCvUrl((String) value);
                    break;
                case "emergencyContact":
                    user.setEmergencyContact((String) value);
                    break;
                case "role":
                    user.setRole(UserRole.valueOf((String) value));
                    break;
                case "profilePictureUrl":
                    user.setProfilePictureUrl((String) value);
                    break;
            }
        });
        return convertToDto(userRepository.save(user));
    }

    public void batchUpdateUser(List<UserPatchDto> updates) {
        updates.forEach(update -> partialUpdateAnUser(update.getUpdates(), update.getId()));
    }

    public void deleteAnUser(Long id) {
        if(!userRepository.existsById(id)) {
            throw new ResourceNotFoundException("User not found with id: " + id);
        } else {
            userRepository.deleteById(id);
        }
    }

}
