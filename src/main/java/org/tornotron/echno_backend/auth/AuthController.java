package org.tornotron.echno_backend.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
        description = "First call a newly signed-up account makes. Sign-up and sign-in happen at the auth "
                + "provider; registration creates the application's user record for that account."
)
public class AuthController {

    private final UserService userService;
    private final IdentityClaimsReader identityReader;

    public AuthController(UserService userService, IdentityClaimsReader identityReader) {
        this.userService = userService;
        this.identityReader = identityReader;
    }

    @PostMapping("/register")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Register the signed-in account",
            description = "Creates the user record for the account the bearer token belongs to. The account "
                    + "is identified by the token's subject and its email is read from the token, so "
                    + "neither is accepted in the body. Called once, after the first sign-in."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User registered"),
            @ApiResponse(responseCode = "400", description = "A field failed validation, or the token carries no email"),
            @ApiResponse(responseCode = "401", description = "No valid bearer token"),
            @ApiResponse(responseCode = "409", description = "The account, or another with its email, is already registered")
    })
    public ResponseEntity<UserDto> registerUser(@AuthenticationPrincipal Jwt jwt,
                                                @Valid @RequestBody UserRegistrationDto userRegistrationDto) {
        UserDto createdUser = userService.registerUser(identityReader.fromJwt(jwt), userRegistrationDto);
        return new ResponseEntity<>(createdUser, HttpStatus.CREATED);
    }
}
