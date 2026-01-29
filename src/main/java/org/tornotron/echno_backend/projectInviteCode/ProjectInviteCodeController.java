package org.tornotron.echno_backend.projectInviteCode;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.organization.dto.OrganizationDto;
import org.tornotron.echno_backend.projectInviteCode.dto.InviteCodeGenerationDto;
import org.tornotron.echno_backend.projectInviteCode.dto.InviteCodeValidationDto;
import org.tornotron.echno_backend.projectInviteCode.dto.ProjectInviteCodeDto;

/**
 * REST controller for managing project and organization invitations.
 * Provides endpoints for generating and validating invite codes.
 */
@RestController
@RequestMapping("/api/v1/invitation")
@Validated
public class ProjectInviteCodeController {

    private final ProjectInviteCodeService projectInviteCodeService;

    /**
     * Constructs a ProjectInviteCodeController with the given ProjectInviteCodeService.
     *
     * @param projectInviteCodeService The service for handling invite code logic.
     */
    public ProjectInviteCodeController(ProjectInviteCodeService projectInviteCodeService) {
        this.projectInviteCodeService = projectInviteCodeService;
    }

    /**
     * Creates a new invite code for an organization.
     *
     * @param inviteCodeGenerationDto DTO containing the details for generating the invite code.
     * @return A {@link ResponseEntity} with the created invite code DTO and HTTP status 201 (Created).
     */
    @PostMapping("/generateCode")
    public ResponseEntity<ProjectInviteCodeDto> createInviteCode(@Valid @RequestBody InviteCodeGenerationDto inviteCodeGenerationDto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projectInviteCodeService.generateInviteCode(inviteCodeGenerationDto));
    }

    /**
     * Validates an invite code and uses it to join an organization.
     *
     * @param inviteCodeValidationDto DTO containing the user ID and the invite code to validate.
     * @return A {@link ResponseEntity} with the organization DTO that was joined and HTTP status 200 (OK).
     */
//    @PostMapping("/validate")
//    public ResponseEntity<OrganizationDto> validateInviteCode(@Valid @RequestBody InviteCodeValidationDto inviteCodeValidationDto) {
//        return ResponseEntity.status(HttpStatus.OK).body(projectInviteCodeService.validateAndUseInviteCode(inviteCodeValidationDto));
//    }
    
}