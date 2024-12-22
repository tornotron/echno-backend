package org.tornotron.echno_backend.projectInviteCode;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
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
        if(created == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Project "+ inviteCodeGenerationDto.getProjectName()+" not found");
        } else if(!created) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Invite code could not be Added");
        } else {
            return ResponseEntity.status(HttpStatus.CREATED).body("InviteCode Created Successfully");
        }
    }

    @PostMapping("/validate")
    public ResponseEntity<String> validateInviteCode(@Valid @ModelAttribute InviteCodeValidationDto inviteCodeValidationDto) {
        Boolean validated = projectInviteCodeService.validateAndUseInviteCode(inviteCodeValidationDto.getCode());
        if(validated == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Invalid Code or Code does not exist");
        } else if (!validated) {
            return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).body("Validation Failed");
        } else {
            return ResponseEntity.status(HttpStatus.OK).body("Code Validated");
        }
    }
    
}
