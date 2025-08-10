package org.tornotron.echno_backend.user.dto;

import lombok.Data;
import org.tornotron.echno_backend.user.enums.UserRole;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class UserDto {
    private Long id;
    private String name;
    private String bloodGroup;
    private String email;
    private String phone;
    private LocalDateTime dateOfBirth;
    private String qualification;
    private List<String> skills;
    private Integer experience;
    private String cvUrl;
    private String emergencyContact;
    private UserRole role;
    private String profilePictureUrl;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
