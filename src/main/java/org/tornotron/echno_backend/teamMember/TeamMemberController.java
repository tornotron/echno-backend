package org.tornotron.echno_backend.teamMember;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.teamMember.dto.TeamMemberCreationDTO;
import org.tornotron.echno_backend.teamMember.dto.TeamMemberDto;
import org.tornotron.echno_backend.teamMember.dto.TeamMemberPatchDto;

import java.util.List;
import java.util.Map;

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
        service.addTeamMember(teamMemberCreationDTO);
        return ResponseEntity.status(HttpStatus.CREATED).body("TeamMember was Created Successfully");
    }

    @GetMapping
    public ResponseEntity<List<TeamMemberDto>> readAllTeamMembers() {
        return new ResponseEntity<>(service.getAllTeamMember(),HttpStatus.OK);
    }

    @GetMapping("{id}")
    public ResponseEntity<?> readATeamMember(@PathVariable Long id) {
        TeamMemberDto teamMember = service.getATeamMember(id);
        return new ResponseEntity<>(teamMember,HttpStatus.OK);
    }

    @PatchMapping("{id}")
    public ResponseEntity<String> partialUpdateATeamMember(@RequestBody Map<String,Object> updates, @PathVariable Long id) {
        service.partialUpdateATeamMember(updates,id);
        return new ResponseEntity<>("TeamMember with id: "+id+" has been updated",HttpStatus.OK);
    }

    @PatchMapping("/batch")
    public ResponseEntity<String> batchUpdateTeamMembers(@Valid @RequestBody List<TeamMemberPatchDto> updates) {
        service.batchUpdateTeamMembers(updates);
        return new ResponseEntity<>("Batch update successful",HttpStatus.OK);
    }

    @DeleteMapping("{id}")
    public ResponseEntity<String> deleteTeamMember(@PathVariable Long id) {
        service.deleteATeamMember(id);
        return new ResponseEntity<>("TeamMember with id: "+id+" deleted",HttpStatus.OK);
    }

}
