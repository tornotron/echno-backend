package org.tornotron.echno_backend.site.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.tornotron.echno_backend.site.model.Team;

public interface TeamRepository extends JpaRepository<Team,Long> {
}
