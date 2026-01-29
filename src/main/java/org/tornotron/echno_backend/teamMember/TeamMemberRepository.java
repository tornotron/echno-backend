package org.tornotron.echno_backend.teamMember;

import org.springframework.data.jpa.repository.JpaRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.teamMember.dto.TeamMemberDto;

import java.util.List;

public interface TeamMemberRepository extends JpaRepository<TeamMember,Long> {
    List<TeamMemberDto> findByProject(Project project);
}
