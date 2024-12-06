package org.tornotron.echno_backend.site.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.site.dto.TeamMemberDto;
import org.tornotron.echno_backend.site.model.TeamMember;
import org.tornotron.echno_backend.site.service.TeamMemberService;

import java.util.List;

@RestController
@RequestMapping("/teamMembers")
public class TeamMemberController {

    private final TeamMemberService service;

    public TeamMemberController(TeamMemberService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<String> createTeamMember(@RequestBody TeamMember teamMember) {
        if(teamMember.getMemberName() == null || teamMember.getMemberName().trim().isEmpty()) {
            return new ResponseEntity<>("'memberName' is a required parameter",HttpStatus.BAD_REQUEST);
        }
        boolean created = service.addTeamMember(teamMember);
        if(created) {
            return new ResponseEntity<>("TeamMember added successfully", HttpStatus.CREATED);
        }
        return new ResponseEntity<>("TeamMember could not be created",HttpStatus.INTERNAL_SERVER_ERROR);
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
