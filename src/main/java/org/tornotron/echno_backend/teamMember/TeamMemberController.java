package org.tornotron.echno_backend.teamMember;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.teamMember.dto.TeamMemberCreationDTO;
import org.tornotron.echno_backend.teamMember.dto.TeamMemberDto;

import java.util.List;

@RestController
@RequestMapping("/teamMembers")
@Validated
public class TeamMemberController {

    private final TeamMemberService service;

    public TeamMemberController(TeamMemberService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<String> createTeamMember(@Valid @RequestBody TeamMemberCreationDTO teamMemberCreationDTO) {
        boolean created = service.addTeamMember(teamMemberCreationDTO);
        if(created) {
            return ResponseEntity.status(HttpStatus.CREATED).body("TeamMember was Created Successfully");
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("TeamMember could not be created");
    }

    @GetMapping
    public ResponseEntity<List<TeamMemberDto>> readAllTeamMembers() {
        return new ResponseEntity<>(service.getAllTeamMember(),HttpStatus.OK);
    }

    @GetMapping("{id}")
    public ResponseEntity<?> readATeamMember(@PathVariable Long id) {
        TeamMemberDto teamMember = service.getATeamMember(id);
        if(teamMember != null) {
            return new ResponseEntity<>(teamMember,HttpStatus.OK);
        }
        return new ResponseEntity<>("TeamMember with id: "+id+" does not exist",HttpStatus.NOT_FOUND);
    }

    @PutMapping("{id}")
    public ResponseEntity<String> updateTeamMember(@RequestBody TeamMember updatedTeamMember,@PathVariable Long id) {
        boolean updated = service.updateATeamMember(updatedTeamMember,id);
        if(updated) {
            return new ResponseEntity<>("TeamMember with id: "+id+" has been updated",HttpStatus.OK);
        }
        return new ResponseEntity<>("TeamMember with id: "+id+" not found",HttpStatus.NOT_FOUND);
    }

    @DeleteMapping("{id}")
    public ResponseEntity<String> deleteTeamMember(@PathVariable Long id) {
        boolean deleted = service.deleteATeamMember(id);
        if(deleted) {
            return new ResponseEntity<>("TeamMember with id: "+id+" deleted",HttpStatus.OK);
        }
        return new ResponseEntity<>("TeamMember with id: "+id+" not found",HttpStatus.NOT_FOUND);
    }

}
