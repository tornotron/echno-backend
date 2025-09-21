package org.tornotron.echno_backend.projectInviteCode;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.organization.dto.OrganizationDto;
import org.tornotron.echno_backend.projectInviteCode.dto.InviteCodeGenerationDto;
import org.tornotron.echno_backend.projectInviteCode.dto.InviteCodeValidationDto;
import org.tornotron.echno_backend.projectInviteCode.dto.ProjectInviteCodeDto;

@RestController
@RequestMapping("/api/v1/invitation")
@Validated
public class ProjectInviteCodeController {

    private final ProjectInviteCodeService projectInviteCodeService;

    public ProjectInviteCodeController(ProjectInviteCodeService projectInviteCodeService) {
        this.projectInviteCodeService = projectInviteCodeService;
    }

    @PostMapping("/generateCode")
    public ResponseEntity<ProjectInviteCodeDto> createInviteCode(@Valid @RequestBody InviteCodeGenerationDto inviteCodeGenerationDto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projectInviteCodeService.generateInviteCode(inviteCodeGenerationDto));
    }

    @PostMapping("/validate")
    public ResponseEntity<OrganizationDto> validateInviteCode(@Valid @RequestBody InviteCodeValidationDto inviteCodeValidationDto) {
        return ResponseEntity.status(HttpStatus.OK).body(projectInviteCodeService.validateAndUseInviteCode(inviteCodeValidationDto));
    }
    
}
