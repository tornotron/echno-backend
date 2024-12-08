package org.tornotron.echno_backend.site.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.site.dto.TeamDto;
import org.tornotron.echno_backend.site.model.Team;
import org.tornotron.echno_backend.site.service.TeamService;

import java.util.List;

@RestController
@RequestMapping("/teams")
@Validated
public class TeamController {

    private final TeamService service;

    public TeamController(TeamService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<String> createTeam(@Valid @RequestBody Team team) {
        boolean created = service.addTeam(team);
        if(created) {
            return ResponseEntity.status(HttpStatus.CREATED).body("Team was Successfully Created");
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Team could not be created");
    }

    @GetMapping
    public ResponseEntity<List<TeamDto>> readAllTeams() {
        return new ResponseEntity<>(service.getAllTeams(),HttpStatus.OK);
    }

    @GetMapping("{id}")
    public ResponseEntity<?> readATeam(@PathVariable Long id) {
        TeamDto team = service.getATeam(id);
        if(team != null) {
            return new ResponseEntity<>(team,HttpStatus.OK);
        }
        return new ResponseEntity<>("Team with id: "+id+" does not exist",HttpStatus.NOT_FOUND);
    }

    @PutMapping("{id}")
    public ResponseEntity<String> updateATeam(@RequestBody Team updatedTeam,@PathVariable Long id) {
        Boolean updated = service.updateTeam(updatedTeam,id);
        if(updated) {
            return new ResponseEntity<>("Team with id: "+id+" has been updated",HttpStatus.OK);
        }
        return new ResponseEntity<>("Team with id: "+id+" not found",HttpStatus.NOT_FOUND);
    }

    @DeleteMapping("{id}")
    public ResponseEntity<String> deleteATeam(@PathVariable Long id) {
        boolean deleted = service.deleteATeam(id);
        if(deleted) {
            return new ResponseEntity<>("Team with id: "+id+" deleted",HttpStatus.OK);
        }
        return new ResponseEntity<>("Team with id: "+id+" not found",HttpStatus.NOT_FOUND);
    }

}
