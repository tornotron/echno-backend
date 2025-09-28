package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.IssueComment.IssueComment;
import org.tornotron.echno_backend.IssueComment.dto.IssueCommentDto;
import org.tornotron.echno_backend.issue.Issue;
import org.tornotron.echno_backend.issue.dto.IssueDto;
import org.tornotron.echno_backend.issue.dto.IssueSimpleDto;

import java.util.stream.Collectors;

@Component
public class IssueDtoConvertor {

    private static IssueCommentDto convertIssueCommentToDto(IssueComment issueComment) {
        IssueCommentDto dto = new IssueCommentDto();
        dto.setId(issueComment.getId());
        dto.setComment(issueComment.getComment());
        dto.setCreatedAt(issueComment.getCreatedAt());
        dto.setAuthor(issueComment.getAuthor());
        return dto;
    }

    public static IssueSimpleDto convertIssueToSimpleDto(Issue issue) {
        IssueSimpleDto dto = new IssueSimpleDto();
        dto.setId(issue.getId());
        dto.setStatus(issue.getStatus());
        dto.setType(issue.getType());
        dto.setDescription(issue.getDescription());
        dto.setTitle(issue.getTitle());
        dto.setCreatedAt(issue.getCreatedAt());
        dto.setUpdatedAt(issue.getUpdatedAt());
        dto.setCreator(issue.getCreator());
        return dto;
    }

    public static IssueDto convertIssueToDto(Issue issue) {
        IssueDto dto = new IssueDto();
        dto.setId(issue.getId());
        dto.setStatus(issue.getStatus());
        dto.setType(issue.getType());
        dto.setDescription(issue.getDescription());
        dto.setTitle(issue.getTitle());
        dto.setCreatedAt(issue.getCreatedAt());
        dto.setUpdatedAt(issue.getUpdatedAt());
        dto.setCreator(issue.getCreator());
        dto.setIssueComments(issue.getIssueComments().stream()
                .map(IssueDtoConvertor::convertIssueCommentToDto)
                .collect(Collectors.toList()));
        return dto;
    }
}
