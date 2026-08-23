package org.tornotron.echno_backend.projectInviteCode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.organization.dto.OrganizationDto;
import org.tornotron.echno_backend.projectInviteCode.dto.InviteCodeGenerationDto;
import org.tornotron.echno_backend.projectInviteCode.dto.InviteCodePatchDto;
import org.tornotron.echno_backend.projectInviteCode.dto.InviteCodeValidationDto;
import org.tornotron.echno_backend.projectInviteCode.dto.ProjectInviteCodeDto;

import java.util.List;

/**
 * REST controller for managing project and organization invitations.
 * Provides endpoints for generating and validating invite codes.
 */
@RestController
@RequestMapping("/api/v1/invitation/web")
@Validated
@Tag(
        name = "Project Invite Codes",
        description = "Invite codes that let users join an organization or project. A code carries a "
                + "usage limit, current usage count and an active flag. Endpoints cover listing an "
                + "organization's codes, generating a code, validating a code to join, and patching a "
                + "code's limits or active state. This is the web-console API, gated by organization "
                + "roles for the current tenant; validation additionally allows the user acting on "
                + "their own account."
)
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

    @GetMapping("/organizationId/{organizationId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "List invite codes for an organization",
            description = "Returns every invite code generated for the given organization."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Invite codes returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<ProjectInviteCodeDto>> readAllInviteCodes(@PathVariable Long organizationId) {
        return ResponseEntity.status(HttpStatus.OK).body(projectInviteCodeService.readAllProjectInviteCodes(organizationId));
    }

    /**
     * Creates a new invite code for an organization.
     *
     * @param inviteCodeGenerationDto DTO containing the details for generating the invite code.
     * @return A {@link ResponseEntity} with the created invite code DTO and HTTP status 201 (Created).
     */
    @PostMapping("/generateCode/organizationId/{organizationId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Generate an invite code",
            description = "Generates a new invite code for the given organization."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Invite code created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No organization with the given id")
    })
    public ResponseEntity<ProjectInviteCodeDto> createInviteCode(@Valid @RequestBody InviteCodeGenerationDto inviteCodeGenerationDto,
                                                                 @PathVariable Long organizationId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projectInviteCodeService.generateInviteCode(inviteCodeGenerationDto,organizationId));
    }

    /**
     * Validates an invite code and uses it to join an organization.
     *
     * @param inviteCodeValidationDto DTO containing the user ID and the invite code to validate.
     * @return A {@link ResponseEntity} with the organization DTO that was joined and HTTP status 200 (OK).
     */
    @PostMapping("/validate/userId/{userId}")
    @PreAuthorize("@orgSecurity.isSelfUser(#userId) or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Validate and use an invite code",
            description = "Validates the supplied invite code and joins the user to the organization it "
                    + "grants access to."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Invite code accepted and organization joined"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation or the code is invalid or expired"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither the target user nor a role holder in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No user with the given id")
    })
    public ResponseEntity<OrganizationDto> validateInviteCode(@Valid @RequestBody InviteCodeValidationDto inviteCodeValidationDto,
                                                              @PathVariable Long userId) {
        return ResponseEntity.status(HttpStatus.OK).body(projectInviteCodeService.validateAndUseInviteCode(inviteCodeValidationDto,userId));
    }

    /**
     * Partially updates an invite code's properties.
     * Use this to adjust maxUses, currentUses, or isActive status.
     *
     * @param inviteCodeId The ID of the invite code to update.
     * @param patchDto     DTO containing the fields to update.
     * @return A {@link ResponseEntity} with the updated invite code DTO and HTTP status 200 (OK).
     */
    @PatchMapping("/{inviteCodeId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Update an invite code",
            description = "Applies a partial update to an invite code, adjusting its usage limit, current "
                    + "usage count or active state."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Invite code updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No invite code with the given id")
    })
    public ResponseEntity<ProjectInviteCodeDto> patchInviteCode(@PathVariable Long inviteCodeId,
                                                                 @Valid @RequestBody InviteCodePatchDto patchDto) {
        return ResponseEntity.ok(projectInviteCodeService.patchInviteCode(inviteCodeId, patchDto));
    }
}