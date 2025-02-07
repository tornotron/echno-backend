package org.tornotron.echno_backend.projectInviteCode;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.exception.DatabaseOperationException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.projectInviteCode.dto.InviteCodeGenerationDto;
import org.tornotron.echno_backend.projectInviteCode.dto.InviteCodeValidationDto;

@RestController
@RequestMapping("/invites")
@Validated
public class ProjectInviteCodeController {

    private final ProjectInviteCodeService projectInviteCodeService;

    public ProjectInviteCodeController(ProjectInviteCodeService projectInviteCodeService) {
        this.projectInviteCodeService = projectInviteCodeService;
    }

    @PostMapping("/createCode")
    public ResponseEntity<String> createInviteCode(@Valid @ModelAttribute InviteCodeGenerationDto inviteCodeGenerationDto) {
        Boolean created = projectInviteCodeService.generateInviteCode(inviteCodeGenerationDto.getProjectName(), inviteCodeGenerationDto.getMaxUses(), inviteCodeGenerationDto.getValidityDays());
         if(!created) {
             throw new DatabaseOperationException("Invite code could not be added");
        } else {
            return ResponseEntity.status(HttpStatus.CREATED).body("InviteCode Created Successfully");
        }
    }

    @PostMapping("/validate")
    public ResponseEntity<String> validateInviteCode(@Valid @ModelAttribute InviteCodeValidationDto inviteCodeValidationDto) {
        Boolean validated = projectInviteCodeService.validateAndUseInviteCode(inviteCodeValidationDto.getCode());
         if (!validated) {
             throw new ResourceNotFoundException("Invite code does not exist");
        } else {
            return ResponseEntity.status(HttpStatus.OK).body("Code Validated");
        }
    }
    
}
