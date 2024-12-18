package org.tornotron.echno_backend.site.repository;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.jpa.repository.JpaRepository;
import org.tornotron.echno_backend.site.model.Project;

public interface ProjectRepository extends JpaRepository<Project,Long> {
    Project findProjectByProjectName(@NotBlank(message = "projectName is required") @Size(min = 3,max = 50,message = "projectName must be between 3 and 50 characters") String projectName);
}
