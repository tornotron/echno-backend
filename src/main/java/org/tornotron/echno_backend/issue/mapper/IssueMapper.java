package org.tornotron.echno_backend.issue.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.IssueComment.IssueComment;
import org.tornotron.echno_backend.IssueComment.dto.IssueCommentDto;
import org.tornotron.echno_backend.common.mapper.AttachmentMapper;
import org.tornotron.echno_backend.issue.Issue;
import org.tornotron.echno_backend.issue.dto.IssueDto;
import org.tornotron.echno_backend.issue.dto.IssueSimpleDto;

/**
 * Maps {@link Issue} to its DTOs. The creator/assignee employees and the task are
 * flattened to id + name (the DTO carries names, not full employee objects); comments
 * map through {@link #toCommentDto}; attachments through {@link AttachmentMapper}.
 */
@Mapper(componentModel = "spring", uses = AttachmentMapper.class)
public interface IssueMapper {

    @Mapping(source = "task.id", target = "taskId")
    @Mapping(source = "task.title", target = "taskName")
    @Mapping(source = "createdBy.id", target = "createdById")
    @Mapping(source = "createdBy.employeeName", target = "createdByName")
    @Mapping(source = "assignedTo.id", target = "assignedToId")
    @Mapping(source = "assignedTo.employeeName", target = "assignedToName")
    IssueDto toDto(Issue issue);

    @Mapping(source = "createdBy.id", target = "createdById")
    @Mapping(source = "createdBy.employeeName", target = "createdByName")
    @Mapping(source = "assignedTo.id", target = "assignedToId")
    @Mapping(source = "assignedTo.employeeName", target = "assignedToName")
    IssueSimpleDto toSimpleDto(Issue issue);

    IssueCommentDto toCommentDto(IssueComment comment);
}
