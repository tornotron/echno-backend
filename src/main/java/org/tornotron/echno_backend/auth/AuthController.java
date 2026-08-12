package org.tornotron.echno_backend.auth;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.user.UserService;
import org.tornotron.echno_backend.user.dto.UserDto;
import org.tornotron.echno_backend.user.dto.UserRegistrationDto;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    @PreAuthorize("permitAll()")
    public ResponseEntity<UserDto> registerUser(@Valid @RequestBody UserRegistrationDto userRegistrationDto) {
        UserDto createdUser = userService.registerUser(userRegistrationDto);
        return new ResponseEntity<>(createdUser, HttpStatus.CREATED);
    }
}
