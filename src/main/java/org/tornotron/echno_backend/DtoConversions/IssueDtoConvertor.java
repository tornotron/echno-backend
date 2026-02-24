package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.IssueComment.IssueComment;
import org.tornotron.echno_backend.IssueComment.dto.IssueCommentDto;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.issue.Issue;
import org.tornotron.echno_backend.issue.dto.IssueDto;
import org.tornotron.echno_backend.issue.dto.IssueSimpleDto;

import java.time.Duration;
import java.util.stream.Collectors;

@Component
public class IssueDtoConvertor {

    private static IssueCommentDto convertIssueCommentToDto(IssueComment issueComment) {
        IssueCommentDto dto = new IssueCommentDto();
        dto.setId(issueComment.getId());
        dto.setComment(issueComment.getComment());
        dto.setCreatedAt(issueComment.getCreatedAt());
        dto.setAuthorId(issueComment.getAuthorId());
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
        if (issue.getCreatedBy() != null) {
            dto.setCreatedById(issue.getCreatedBy().getId());
            dto.setCreatedByName(issue.getCreatedBy().getEmployeeName());
        }
        if (issue.getAssignedTo() != null) {
            dto.setAssignedToId(issue.getAssignedTo().getId());
            dto.setAssignedToName(issue.getAssignedTo().getEmployeeName());
        }
        return dto;
    }

    public static AttachmentDto convertAttachmentToDto(Attachment attachment, FileStorageService fileStorageService) {
        AttachmentDto dto = new AttachmentDto();
        dto.setId(attachment.getId());
        dto.setUrl(fileStorageService.generateDownloadUrl(attachment.getStorageKey(), Duration.ofHours(1)));
        dto.setEntityType(attachment.getEntityType());
        dto.setContentType(attachment.getContentType());
        dto.setFileSize(attachment.getFileSize());
        dto.setFileName(attachment.getOriginalFilename());
        dto.setCreatedAt(attachment.getCreatedAt().toString());
        dto.setUpdatedAt(attachment.getUpdatedAt().toString());
        return dto;
    }

    public static IssueDto convertIssueToDto(Issue issue, FileStorageService fileStorageService) {
        IssueDto dto = new IssueDto();
        dto.setId(issue.getId());
        dto.setStatus(issue.getStatus());
        dto.setType(issue.getType());
        dto.setDescription(issue.getDescription());
        dto.setTitle(issue.getTitle());
        dto.setCreatedAt(issue.getCreatedAt());
        dto.setUpdatedAt(issue.getUpdatedAt());
        dto.setTaskId(issue.getTask().getId());
        dto.setTaskName(issue.getTask().getTitle());
        if (issue.getCreatedBy() != null) {
            dto.setCreatedById(issue.getCreatedBy().getId());
            dto.setCreatedByName(issue.getCreatedBy().getEmployeeName());
        }
        if (issue.getAssignedTo() != null) {
            dto.setAssignedToId(issue.getAssignedTo().getId());
            dto.setAssignedToName(issue.getAssignedTo().getEmployeeName());
        }
        dto.setIssueComments(issue.getIssueComments().stream()
                .map(IssueDtoConvertor::convertIssueCommentToDto)
                .collect(Collectors.toList()));
        dto.setAttachments(issue.getAttachments().stream()
                .map(attachment -> convertAttachmentToDto(attachment, fileStorageService))
                .collect(Collectors.toList()));
        return dto;
    }
}
