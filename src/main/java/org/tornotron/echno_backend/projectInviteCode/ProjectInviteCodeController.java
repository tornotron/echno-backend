package org.tornotron.echno_backend.projectInviteCode;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.projectInviteCode.dto.InviteCodeGenerationDto;
import org.tornotron.echno_backend.projectInviteCode.dto.InviteCodeValidationDto;

@RestController
@RequestMapping("/api/v1/invites")
@Validated
public class ProjectInviteCodeController {

    private final ProjectInviteCodeService projectInviteCodeService;

    public ProjectInviteCodeController(ProjectInviteCodeService projectInviteCodeService) {
        this.projectInviteCodeService = projectInviteCodeService;
    }

    @PostMapping("/createCode")
    public ResponseEntity<ApiResponse> createInviteCode(@Valid @RequestBody InviteCodeGenerationDto inviteCodeGenerationDto) {
        projectInviteCodeService.generateInviteCode(inviteCodeGenerationDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse("Invite Code Created Successfully"));
    }

    @PostMapping("/validate")
    public ResponseEntity<ApiResponse> validateInviteCode(@Valid @RequestBody InviteCodeValidationDto inviteCodeValidationDto) {
        projectInviteCodeService.validateAndUseInviteCode(inviteCodeValidationDto);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Invite Code Validated Successfully"));
    }
    
}
