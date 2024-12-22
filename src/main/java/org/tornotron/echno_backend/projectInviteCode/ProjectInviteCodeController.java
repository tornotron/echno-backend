package org.tornotron.echno_backend.projectInviteCode;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/invites")
public class ProjectInviteCodeController {

    private final ProjectInviteCodeService projectInviteCodeService;

    public ProjectInviteCodeController(ProjectInviteCodeService projectInviteCodeService) {
        this.projectInviteCodeService = projectInviteCodeService;
    }

    @PostMapping("/createCode")
    public ResponseEntity<String> createInviteCode(@RequestParam String projectName,
                                   @RequestParam(defaultValue = "1") int maxUses,
                                   @RequestParam(defaultValue = "7") int validityDays) {
        Boolean created = projectInviteCodeService.generateInviteCode(projectName,maxUses,validityDays);
        if(created == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Project "+projectName+" not found");
        } else if(!created) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Invite code could not be Added");
        } else {
            return ResponseEntity.status(HttpStatus.CREATED).body("InviteCode Created Successfully");
        }
    }

    @PostMapping("/validate")
    public ResponseEntity<String> validateInviteCode(@RequestParam int code) {
        Boolean validated = projectInviteCodeService.validateAndUseInviteCode(code);
        if(validated == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Invalid Code or Code does not exist");
        } else if (!validated) {
            return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).body("Validation Failed");
        } else {
            return ResponseEntity.status(HttpStatus.OK).body("Code Validated");
        }
    }
    
}
