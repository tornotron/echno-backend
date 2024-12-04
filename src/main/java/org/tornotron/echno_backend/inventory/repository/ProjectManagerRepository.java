package org.tornotron.echno_backend.inventory.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.tornotron.echno_backend.inventory.model.ProjectManager;

public interface ProjectManagerRepository extends JpaRepository<ProjectManager,Long> {
}
