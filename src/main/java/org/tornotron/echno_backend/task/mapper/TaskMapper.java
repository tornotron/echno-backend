package org.tornotron.echno_backend.task.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.category.mapper.CategoryMapper;
import org.tornotron.echno_backend.common.mapper.AttachmentMapper;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.issue.mapper.IssueMapper;
import org.tornotron.echno_backend.task.Task;
import org.tornotron.echno_backend.task.dto.TaskDto;
import org.tornotron.echno_backend.task.dto.TaskSimpleDto;

/**
 * Maps {@link Task} to its DTOs. creator/assignees map to full employee DTOs via
 * {@link EmployeeMapper}, category via {@link CategoryMapper}, issues via
 * {@link IssueMapper}, attachments via {@link AttachmentMapper}; the project flattens to
 * its id. The simple DTO carries the creator/project/category ids instead.
 */
@Mapper(componentModel = "spring",
        uses = {EmployeeMapper.class, CategoryMapper.class, IssueMapper.class, AttachmentMapper.class})
public interface TaskMapper {

    @Mapping(source = "project.id", target = "projectId")
    TaskDto toDto(Task task);

    @Mapping(source = "creator.id", target = "creatorId")
    @Mapping(source = "project.id", target = "projectId")
    @Mapping(source = "category.id", target = "categoryId")
    TaskSimpleDto toSimpleDto(Task task);
}
