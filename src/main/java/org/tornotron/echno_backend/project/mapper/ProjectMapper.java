package org.tornotron.echno_backend.project.mapper;

import org.mapstruct.AfterMapping;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.tornotron.echno_backend.common.mapper.AttachmentMapper;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectProgressCalculator;
import org.tornotron.echno_backend.project.ProjectProgressLookup;
import org.tornotron.echno_backend.project.dto.ProjectDto;
import org.tornotron.echno_backend.project.dto.ProjectSimpleDto;
import org.tornotron.echno_backend.project.dto.ProjectSummaryDto;
import org.tornotron.echno_backend.task.mapper.TaskMapper;

/**
 * Maps {@link Project} to its DTOs. employees via {@link EmployeeMapper}, tasks via
 * {@link TaskMapper}, attachments via {@link AttachmentMapper}. On the full DTO, progress
 * is the average of the tasks' progress (computed in {@link #calcProgress}); on the simple
 * DTO it is the entity's own progress field, mapped by name.
 *
 * <p>{@link #toSummaryDto} is the list projection of the full DTO: the same scalar fields, none
 * of the collections, and a progress figure that means the same thing. It is deliberately not
 * {@link #toSimpleDto}, which is the shape a create or an update replies with and whose progress
 * comes from the entity's own {@code progress} column. Nothing in the codebase writes that
 * column, so on the simple DTO the field is always null; the full DTO derives the number from the
 * tasks instead, and the summary reads the same derivation out of the
 * {@link ProjectProgressLookup} the caller fills for the whole page.
 */
@Mapper(componentModel = "spring",
        uses = {EmployeeMapper.class, TaskMapper.class, AttachmentMapper.class})
public interface ProjectMapper {

    @Mapping(target = "progress", ignore = true) // computed in calcProgress
    ProjectDto toDto(Project project);

    ProjectSimpleDto toSimpleDto(Project project);

    /**
     * Converts a project for a list, taking its progress from the supplied lookup.
     *
     * @param project The project to convert.
     * @param progress The average task progress read for the whole page of projects being mapped.
     *                 A project absent from it reads as zero, which is what
     *                 {@link ProjectProgressCalculator} returns for a project with no tasks.
     * @return The project summary.
     */
    @Mapping(target = "progress", expression = "java(progress.progressOf(project.getId()))")
    ProjectSummaryDto toSummaryDto(Project project, @Context ProjectProgressLookup progress);

    @AfterMapping
    default void calcProgress(Project project, @MappingTarget ProjectDto dto) {
        dto.setProgress(ProjectProgressCalculator.calculate(project.getTasks()));
    }
}
