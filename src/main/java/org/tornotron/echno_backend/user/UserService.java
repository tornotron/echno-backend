package org.tornotron.echno_backend.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.OrganizationDtoConvertor;
import org.tornotron.echno_backend.DtoConversions.UserDtoConvertor;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.organization.dto.OrganizationDto;
import org.tornotron.echno_backend.user.dto.UserCreationDto;
import org.tornotron.echno_backend.user.dto.UserDto;
import org.tornotron.echno_backend.user.dto.UserPatchDto;
import org.tornotron.echno_backend.user.enums.UserRole;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }



    public UserDto addUser(UserCreationDto userCreationDto) {
        User user = new User();
        user.setName(userCreationDto.getName());
        user.setGender(userCreationDto.getGender());
        user.setAddress(userCreationDto.getAddress());
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
        return UserDtoConvertor.convertUserToDto(userRepository.save(user));
    }

    @Transactional(readOnly = true)
    public List<OrganizationDto> getOrganizationsForCurrentUser(Long userId) {
        return userRepository.findOrganizationsByUserId(userId)
                .stream()
                .map(OrganizationDtoConvertor::convertOrganizationToDto)
                .collect(Collectors.toList());

    }

    @Transactional(readOnly = true)
    public Page<UserDto> getAllUsers(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo,pageSize, Sort.by(Sort.Direction.ASC,"id"));
        return userRepository.findAll(pageable)
                .map(UserDtoConvertor::convertUserToDto);
    }

    @Transactional(readOnly = true)
    public UserDto getAnUser(Long id) {
        return userRepository.findById(id)
                .map(UserDtoConvertor::convertUserToDto)
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
                case "gender":
                    user.setGender((String) value);
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
        return UserDtoConvertor.convertUserToDto(userRepository.save(user));
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
