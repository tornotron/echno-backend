package org.tornotron.echno_backend.teamMember;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.teamMember.dto.TeamMemberCreationDTO;
import org.tornotron.echno_backend.teamMember.dto.TeamMemberDto;
import org.tornotron.echno_backend.teamMember.dto.TeamMemberPatchDto;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/teamMembers/web")
@Validated
public class TeamMemberControllerWeb {

    private final TeamMemberService service;

    public TeamMemberControllerWeb(TeamMemberService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('team-member:create') or hasAuthority('team-member:admin')")
    public ResponseEntity<ApiResponse> createTeamMember(@Valid @RequestBody TeamMemberCreationDTO teamMemberCreationDTO) {
        service.addTeamMember(teamMemberCreationDTO);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse("TeamMember Created Successfully"));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('team-member:read') or hasAuthority('team-member:admin')")
    public ResponseEntity<List<TeamMemberDto>> readAllTeamMembers() {
        return new ResponseEntity<>(service.getAllTeamMember(),HttpStatus.OK);
    }

    @GetMapping("{id}")
    @PreAuthorize("hasAuthority('team-member:read') or hasAuthority('team-member:admin')")
    public ResponseEntity<?> readATeamMember(@PathVariable Long id) {
        TeamMemberDto teamMember = service.getATeamMember(id);
        return new ResponseEntity<>(teamMember,HttpStatus.OK);
    }

    @PatchMapping("{id}")
    @PreAuthorize("hasAuthority('team-member:update') or hasAuthority('team-member:admin')")
    public ResponseEntity<ApiResponse> partialUpdateATeamMember(@RequestBody Map<String,Object> updates, @PathVariable Long id) {
        service.partialUpdateATeamMember(updates,id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("TeamMember with id: "+id+" has been updated"));
    }

    @PatchMapping("/batch")
    @PreAuthorize("hasAuthority('team-member:update') or hasAuthority('team-member:admin')")
    public ResponseEntity<ApiResponse> batchUpdateTeamMembers(@Valid @RequestBody List<TeamMemberPatchDto> updates) {
        service.batchUpdateTeamMembers(updates);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Batch update successful"));
    }

    @DeleteMapping("{id}")
    @PreAuthorize("hasAuthority('team-member:delete') or hasAuthority('team-member:admin')")
    public ResponseEntity<ApiResponse> deleteTeamMember(@PathVariable Long id) {
        service.deleteATeamMember(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("TeamMember with id: "+id+" has been deleted"));
    }

}
