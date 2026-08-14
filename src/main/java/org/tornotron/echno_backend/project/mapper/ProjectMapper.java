package org.tornotron.echno_backend.project.mapper;

import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.tornotron.echno_backend.common.mapper.AttachmentMapper;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectProgressCalculator;
import org.tornotron.echno_backend.project.dto.ProjectDto;
import org.tornotron.echno_backend.project.dto.ProjectSimpleDto;
import org.tornotron.echno_backend.task.mapper.TaskMapper;

/**
 * Maps {@link Project} to its DTOs. employees via {@link EmployeeMapper}, tasks via
 * {@link TaskMapper}, attachments via {@link AttachmentMapper}. On the full DTO, progress
 * is the average of the tasks' progress (computed in {@link #calcProgress}); on the simple
 * DTO it is the entity's own progress field, mapped by name.
 */
@Mapper(componentModel = "spring",
        uses = {EmployeeMapper.class, TaskMapper.class, AttachmentMapper.class})
public interface ProjectMapper {

    @Mapping(target = "progress", ignore = true) // computed in calcProgress
    ProjectDto toDto(Project project);

    ProjectSimpleDto toSimpleDto(Project project);

    @AfterMapping
    default void calcProgress(Project project, @MappingTarget ProjectDto dto) {
        dto.setProgress(ProjectProgressCalculator.calculate(project.getTasks()));
    }
}
