package org.tornotron.echno_backend.teamMember;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.TeamMemberDtoConvertor;
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

  public TeamMemberService(TeamMemberRepository repository, ProjectRepository projectRepository) {
    this.repository = repository;
    this.projectRepository = projectRepository;
  }

  @Transactional
  public void addTeamMember(TeamMemberCreationDTO teamMemberCreationDTO) {
    Project project = projectRepository.findProjectByProjectName(teamMemberCreationDTO.getProjectName());
    TeamMember teamMember = new TeamMember();
    teamMember.setMemberName(teamMemberCreationDTO.getMemberName());
    teamMember.setMemberEmail(teamMemberCreationDTO.getMemberEmail());
    teamMember.setMemberPhone(teamMemberCreationDTO.getMemberPhone());
    teamMember.setMemberRole(teamMemberCreationDTO.getMemberRole());
    teamMember.setMemberImage(teamMemberCreationDTO.getMemberImage());
    teamMember.setProject(project);
    TeamMember savedTeamMember = repository.save(teamMember);
    if (savedTeamMember.getId() == null) {
      throw new DatabaseOperationException("TeamMember could not be created");
    }

  }

  @Transactional(readOnly = true)
  public List<TeamMemberDto> getAllTeamMember() {
    return repository.findAll().stream()
        .map(TeamMemberDtoConvertor::convertTeamMemberToDTO)
        .collect(Collectors.toList());
  }

  @Transactional(readOnly = true)
  public TeamMemberDto getATeamMember(Long id) {
    TeamMemberDto teamMemberDto = repository.findById(id)
        .map(TeamMemberDtoConvertor::convertTeamMemberToDTO)
        .orElse(null);
    if (teamMemberDto == null) {
      throw new ResourceNotFoundException("TeamMember not found with id: " + id);
    } else {
      return teamMemberDto;
    }
  }

  @Transactional
  public void partialUpdateATeamMember(Map<String, Object> updates, Long id) {
    TeamMember teamMember = repository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("TeamMember not found with id: " + id));
    partialUpdateATeamMember(updates, teamMember);
    repository.save(teamMember);
  }

  private void partialUpdateATeamMember(Map<String, Object> updates, TeamMember teamMember) {
    updates.forEach((key, value) -> {
      switch (key) {
        case "memberName":
          teamMember.setMemberName((String) value);
          break;
        case "memberEmail":
          teamMember.setMemberEmail((String) value);
          break;
      }
    });
  }

  @Transactional
  public void batchUpdateTeamMembers(List<TeamMemberPatchDto> updates) {
    List<Long> teamMemberIds = updates.stream().map(TeamMemberPatchDto::getId).collect(Collectors.toList());
    List<TeamMember> teamMembers = repository.findAllById(teamMemberIds);

    Map<Long, TeamMember> teamMemberMap = teamMembers.stream().collect(Collectors.toMap(TeamMember::getId, teamMember -> teamMember));

    updates.forEach(update -> {
        TeamMember teamMember = teamMemberMap.get(update.getId());
        if (teamMember != null) {
            partialUpdateATeamMember(update.getUpdates(), teamMember);
        }
    });

    repository.saveAll(teamMembers);
  }

  @Transactional
  public void deleteATeamMember(Long id) {
    if (!repository.existsById(id)) {
      throw new ResourceNotFoundException("TeamMember not found with id: " + id);
    }
    repository.deleteById(id);
  }
}
