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
 * {@link TaskMapper}, attachments via {@link AttachmentMapper}. Progress means one thing on
 * every shape: the average of the project's task progress values. The full DTO and the simple
 * DTO each compute it through {@link ProjectProgressCalculator} (in {@link #calcProgress} and
 * {@link #calcSimpleProgress}); the summary reads the same average out of the
 * {@link ProjectProgressLookup} the caller fills for the whole page.
 *
 * <p>{@link #toSummaryDto} is the list projection of the full DTO: the same scalar fields, none
 * of the collections, and a progress figure that means the same thing. It is deliberately not
 * {@link #toSimpleDto}, which is the shape a create or an update replies with and which carries
 * no collections at all.
 *
 * <p>The entity still declares a {@code progress} column, but nothing writes it and nothing here
 * reads it. Every mapping above ignores it. It is left in the schema rather than dropped, because
 * removing a column is a migration with its own risk; treat it as dead until then.
 */
@Mapper(componentModel = "spring",
        uses = {EmployeeMapper.class, TaskMapper.class, AttachmentMapper.class})
public interface ProjectMapper {

    @Mapping(target = "progress", ignore = true) // computed in calcProgress
    ProjectDto toDto(Project project);

    @Mapping(target = "progress", ignore = true) // computed in calcSimpleProgress
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

    /**
     * Derives the progress a create or an update replies with, the same way the read shape does.
     *
     * <p>Both call sites run inside the service transaction that saved the project, so reaching
     * the tasks is a lazy association load rather than a detached access. A project being created
     * has none, and the calculator answers {@code 0.0} for that.
     *
     * @param project The project being mapped.
     * @param dto The simple DTO to fill in.
     */
    @AfterMapping
    default void calcSimpleProgress(Project project, @MappingTarget ProjectSimpleDto dto) {
        dto.setProgress(ProjectProgressCalculator.calculate(project.getTasks()));
    }
}
