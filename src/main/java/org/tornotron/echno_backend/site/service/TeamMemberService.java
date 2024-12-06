package org.tornotron.echno_backend.site.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.site.dto.TeamMemberDto;
import org.tornotron.echno_backend.site.model.TeamMember;
import org.tornotron.echno_backend.site.repository.TeamMemberRepository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class TeamMemberService {

    private final TeamMemberRepository repository;
    private static final Logger logger = LoggerFactory.getLogger(TeamMemberService.class);

    public TeamMemberService(TeamMemberRepository repository) {
        this.repository = repository;
    }

    public TeamMemberDto convertToDTO(TeamMember teamMember) {
        TeamMemberDto dto = new TeamMemberDto();
        dto.setId(teamMember.getId());
        dto.setMemberName(teamMember.getMemberName());
        dto.setMemberEmail(teamMember.getMemberEmail());
        return dto;
    }

    public boolean addTeamMember(TeamMember teamMember) {
        if(teamMember == null) {
            logger.warn("Attempted to add null teamMember");
            return false;
        }
        try {
            repository.save(teamMember);
            logger.info("TeamMember added successfully: ID={}, NAME={}",teamMember.getId(),teamMember.getMemberName());
            return true;
        } catch (Exception e) {
            logger.error("Data could not be added to database");
            return false;
        }
    }

    public List<TeamMemberDto> getAllTeamMember() {
        return repository.findAll().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    public TeamMemberDto getATeamMember(Long id) {
        return repository.findById(id)
                .map(this::convertToDTO)
                .orElse(null);
    }

    public boolean updateATeamMember(TeamMember updatedTeamMember,Long id) {
        Optional<TeamMember> optionalTeamMember = repository.findById(id);
        if(optionalTeamMember.isPresent()) {
            TeamMember teamMemberObj = optionalTeamMember.get();
            teamMemberObj.setMemberName(updatedTeamMember.getMemberName());
            teamMemberObj.setMemberEmail(updatedTeamMember.getMemberEmail());
            return addTeamMember(teamMemberObj);
        }
        return false;
    }

    public boolean deleteATeamMember(Long id) {
        try {
            repository.deleteById(id);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
