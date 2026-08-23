package org.tornotron.echno_backend.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(
        name = "Auth",
        description = "Unauthenticated entry point for creating a new account. Registration provisions "
                + "the Keycloak identity behind a user and the corresponding user record in one call."
)
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    @PreAuthorize("permitAll()")
    @Operation(
            summary = "Register a new user",
            description = "Creates a Keycloak identity and a user record from the given registration "
                    + "details. Open to unauthenticated callers."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User registered"),
            @ApiResponse(responseCode = "400", description = "A field failed validation, or the username/email is already taken")
    })
    public ResponseEntity<UserDto> registerUser(@Valid @RequestBody UserRegistrationDto userRegistrationDto) {
        UserDto createdUser = userService.registerUser(userRegistrationDto);
        return new ResponseEntity<>(createdUser, HttpStatus.CREATED);
    }
}
