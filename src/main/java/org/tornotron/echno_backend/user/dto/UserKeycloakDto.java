package org.tornotron.echno_backend.user.dto;

import lombok.Data;

@Data
public class UserKeycloakDto {
    private String userName;
    private String emailId;
    private String password;
    private String firstName;
    private String lastName;
    private boolean isAdmin;
}
