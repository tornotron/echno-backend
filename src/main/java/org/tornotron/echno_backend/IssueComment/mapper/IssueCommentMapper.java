package org.tornotron.echno_backend.IssueComment.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.IssueComment.IssueComment;
import org.tornotron.echno_backend.IssueComment.dto.IssueCommentDto;
import org.tornotron.echno_backend.IssueComment.dto.IssueCommentSimpleDto;

/** Maps {@link IssueComment} to its full and simple DTOs. All fields map by name. */
@Mapper(componentModel = "spring")
public interface IssueCommentMapper {

    IssueCommentDto toDto(IssueComment issueComment);

    IssueCommentSimpleDto toSimpleDto(IssueComment issueComment);
}
