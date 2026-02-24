package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.IssueComment.IssueComment;
import org.tornotron.echno_backend.IssueComment.dto.IssueCommentDto;
import org.tornotron.echno_backend.IssueComment.dto.IssueCommentSimpleDto;

@Component
public class IssueCommentDtoConvertor {

    public static IssueCommentSimpleDto convertIssueCommentToSimpleDto(IssueComment issueComment) {
        IssueCommentSimpleDto simpleDto = new IssueCommentSimpleDto();
        simpleDto.setId(issueComment.getId());
        simpleDto.setAuthorId(issueComment.getAuthorId());
        simpleDto.setComment(issueComment.getComment());
        simpleDto.setCreatedAt(issueComment.getCreatedAt());
        return simpleDto;
    }


    public static IssueCommentDto convertIssueCommentToDto(IssueComment issueComment) {
        IssueCommentDto dto = new IssueCommentDto();
        dto.setId(issueComment.getId());
        dto.setAuthorId(issueComment.getAuthorId());
        dto.setComment(issueComment.getComment());
        dto.setCreatedAt(issueComment.getCreatedAt());
        return dto;
    }
}
