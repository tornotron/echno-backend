package org.tornotron.echno_backend.site.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.site.model.Project;
import org.tornotron.echno_backend.site.repository.ProjectRepository;

@Service
public class ProjectService {
    private static final Logger logger = LoggerFactory.getLogger(ProjectService.class);
    private final ProjectRepository repository;

    public ProjectService(ProjectRepository repository) {
        this.repository = repository;
    }

    private void validateProject(Project project) {
        if(project.getProjectName() == null || project.getProjectName().trim().isEmpty()) {
            throw new IllegalArgumentException("Project name cannot be empty");
        }
    }

    public boolean addProject(Project project) {
        if(project == null) {
            logger.warn("Attempted to add null project");
            return false;
        }
        try {
            validateProject(project);
            repository.save(project);
            logger.info("Project added successfully: ID={}, NAME={}",project.getId(),project.getProjectName());
            return true;
        } catch (Exception e) {
            logger.error("Data could not be added to database");
            return false;
        }
    }
}
