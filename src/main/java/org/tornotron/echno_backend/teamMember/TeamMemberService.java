package org.tornotron.echno_backend.teamMember;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.exception.DatabaseOperationException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.teamMember.dto.TeamMemberCreationDTO;
import org.tornotron.echno_backend.teamMember.dto.TeamMemberDto;
import org.tornotron.echno_backend.teamMember.dto.TeamMemberPatchDto;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class TeamMemberService {

    private final TeamMemberRepository repository;
    private final ProjectRepository projectRepository;
    private static final Logger logger = LoggerFactory.getLogger(TeamMemberService.class);

    public TeamMemberService(TeamMemberRepository repository,ProjectRepository projectRepository) {
        this.repository = repository;
        this.projectRepository = projectRepository;
    }

    private TeamMemberDto convertToDTO(TeamMember teamMember) {
        TeamMemberDto dto = new TeamMemberDto();
        dto.setId(teamMember.getId());
        dto.setMemberName(teamMember.getMemberName());
        dto.setMemberEmail(teamMember.getMemberEmail());
        return dto;
    }

    public void addTeamMember(TeamMemberCreationDTO teamMemberCreationDTO) {
        Project project = projectRepository.findProjectByProjectName(teamMemberCreationDTO.getProjectName());
        TeamMember teamMember = new TeamMember();
        teamMember.setMemberName(teamMemberCreationDTO.getMemberName());
        teamMember.setMemberEmail(teamMemberCreationDTO.getMemberEmail());
        teamMember.setProject(project);
        TeamMember savedTeamMember = repository.save(teamMember);
        if(savedTeamMember.getId() == null) {
            throw new DatabaseOperationException("TeamMember could not be created");
        }

    }

    public List<TeamMemberDto> getAllTeamMember() {
        return repository.findAll().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    public TeamMemberDto getATeamMember(Long id) {
        TeamMemberDto teamMemberDto = repository.findById(id)
                .map(this::convertToDTO)
                .orElse(null);
        if(teamMemberDto == null) {
            throw new ResourceNotFoundException("TeamMember not found with id: "+id);
        }else {
            return teamMemberDto;
        }
    }

    public void partialUpdateATeamMember(Map<String,Object> updates, Long id) {
        TeamMember teamMember = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("TeamMember not found with id: "+id));

        updates.forEach((key,value) -> {
            switch (key) {
                case "memberName":
                    teamMember.setMemberName((String) value);
                    break;
                case "memberEmail":
                    teamMember.setMemberEmail((String) value);
                    break;
            }
        });
        repository.save(teamMember);
    }

    public void batchUpdateTeamMembers(List<TeamMemberPatchDto> updates) {
        updates.forEach(update ->
                partialUpdateATeamMember(update.getUpdates(),update.getId()));
    }


    public void deleteATeamMember(Long id) {
        if(!repository.existsById(id)) {
            throw new ResourceNotFoundException("TeamMember not found with id: "+id);
        }
        repository.deleteById(id);
    }
}
