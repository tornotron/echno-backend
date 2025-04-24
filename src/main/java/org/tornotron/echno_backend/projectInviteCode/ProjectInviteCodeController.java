package org.tornotron.echno_backend.projectInviteCode;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.projectInviteCode.dto.InviteCodeGenerationDto;
import org.tornotron.echno_backend.projectInviteCode.dto.InviteCodeValidationDto;

@RestController
@RequestMapping("/api/invites")
@Validated
public class ProjectInviteCodeController {

    private final ProjectInviteCodeService projectInviteCodeService;

    public ProjectInviteCodeController(ProjectInviteCodeService projectInviteCodeService) {
        this.projectInviteCodeService = projectInviteCodeService;
    }

    @PostMapping("/createCode")
    public ResponseEntity<String> createInviteCode(@Valid @RequestBody InviteCodeGenerationDto inviteCodeGenerationDto) {
        projectInviteCodeService.generateInviteCode(inviteCodeGenerationDto.getProjectName(), inviteCodeGenerationDto.getMaxUses(), inviteCodeGenerationDto.getValidityDays());
        return ResponseEntity.status(HttpStatus.CREATED).body("InviteCode Created Successfully");
    }

    @PostMapping("/validate")
    public ResponseEntity<String> validateInviteCode(@Valid @RequestBody InviteCodeValidationDto inviteCodeValidationDto) {
        projectInviteCodeService.validateAndUseInviteCode(Integer.parseInt(inviteCodeValidationDto.getCode()));
        return ResponseEntity.status(HttpStatus.OK).body("Code Validated");
    }
    
}
