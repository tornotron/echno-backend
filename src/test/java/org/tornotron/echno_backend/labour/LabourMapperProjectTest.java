package org.tornotron.echno_backend.labour;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.labour.dto.LabourDto;
import org.tornotron.echno_backend.labour.mapper.LabourMapper;
import org.tornotron.echno_backend.labour.mapper.LabourMapperImpl;
import org.tornotron.echno_backend.project.Project;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The labour list and detail are built from {@link LabourDto}, which never carried the worker's
 * current project although the assignment was saved (#862).
 */
class LabourMapperProjectTest {

    private final LabourMapper mapper = new LabourMapperImpl();

    @Test
    void toDto_carriesTheCurrentProject() {
        Project project = new Project();
        project.setId(4L);
        project.setProjectName("Marina Heights Towers");
        Labour labour = new Labour();
        labour.setCurrentProject(project);

        LabourDto dto = mapper.toDto(labour);

        assertThat(dto.getCurrentProjectId()).isEqualTo(4L);
        assertThat(dto.getCurrentProjectName()).isEqualTo("Marina Heights Towers");
    }

    @Test
    void toDto_leavesTheProjectEmptyForAnUnassignedWorker() {
        LabourDto dto = mapper.toDto(new Labour());

        assertThat(dto.getCurrentProjectId()).isNull();
        assertThat(dto.getCurrentProjectName()).isNull();
    }
}
