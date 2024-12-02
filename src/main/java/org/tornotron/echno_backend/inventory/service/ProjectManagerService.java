package org.tornotron.echno_backend.inventory.service;

import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.inventory.Repository.ProjectManagerRepository;
import org.tornotron.echno_backend.inventory.model.ProjectManager;


@Service
public class ProjectManagerService {

    private final ProjectManagerRepository repository;

    public ProjectManagerService(ProjectManagerRepository repository) {
        this.repository = repository;
    }

    public boolean addProjectManager(ProjectManager projectManager) {
        try {
            repository.save(projectManager);

        } catch (Exception e) {

        }
    }

}
