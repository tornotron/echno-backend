package org.tornotron.echno_backend.site.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.tornotron.echno_backend.site.model.TeamMember;

public interface TeamMemberRepository extends JpaRepository<TeamMember,Long> {
}
