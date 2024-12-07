package org.tornotron.echno_backend.site.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.site.dto.TeamDto;
import org.tornotron.echno_backend.site.dto.TeamMemberDto;
import org.tornotron.echno_backend.site.model.Team;
import org.tornotron.echno_backend.site.model.TeamMember;
import org.tornotron.echno_backend.site.repository.TeamRepository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class TeamService {

    private static final Logger logger = LoggerFactory.getLogger(TeamService.class);
    private final TeamRepository repository;

    public TeamService(TeamRepository repository) {
        this.repository = repository;
    }

    private TeamMemberDto convertTeamMemberToTeamMemberDto(TeamMember teamMember) {
        TeamMemberDto teamMemberDto = new TeamMemberDto();
        teamMemberDto.setId(teamMember.getId());
        teamMemberDto.setMemberName(teamMember.getMemberName());
        teamMemberDto.setMemberEmail(teamMember.getMemberEmail());
        return teamMemberDto;
    }

    private TeamDto convertToDTO(Team team) {
        TeamDto dto = new TeamDto();
        dto.setId(team.getId());
        dto.setTeamName(team.getTeamName());
        dto.setTeamMembers(team.getMembers().stream()
                .map(this::convertTeamMemberToTeamMemberDto)
                .collect(Collectors.toList()));
        return dto;
    }

    public boolean addTeam(Team team) {
        if(team == null) {
            logger.warn("Attempted to add null team");
            return false;
        }
        try {
            repository.save(team);
            logger.info("Team added successfully: ID={}, NAME={}",team.getId(),team.getTeamName());
            return true;
        } catch (Exception e) {
            logger.error("Data could not be added to database");
            return false;
        }
    }

    public List<TeamDto> getAllTeams() {
       return repository.findAll().stream()
               .map(this::convertToDTO)
               .collect(Collectors.toList());
    }

    public TeamDto getATeam(Long id) {
        return repository.findById(id)
                .map(this::convertToDTO)
                .orElse(null);
    }

    public Boolean updateTeam(Team updatedTeam,Long id) {
        Optional<Team> optionalTeam = repository.findById(id);
        if(optionalTeam.isPresent()) {
            Team teamObj = optionalTeam.get();
            teamObj.setTeamName(updatedTeam.getTeamName());
            return addTeam(teamObj);
        }
        return false;
    }

    public Boolean deleteATeam(Long id) {
        try {
            repository.deleteById(id);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

