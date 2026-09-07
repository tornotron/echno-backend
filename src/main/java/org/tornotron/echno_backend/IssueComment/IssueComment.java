package org.tornotron.echno_backend.IssueComment;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.tornotron.echno_backend.issue.Issue;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "Issue_comments")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class IssueComment implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "author_id",nullable = false)
    private Long authorId;

    @ManyToOne
    private Issue issue;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @Column(name = "comment", nullable = false)
    private String comment;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Whether the author has changed the text since posting it.
     *
     * <p>Set only by {@code IssueCommentService.updateIssueComment}, never by
     * {@code @UpdateTimestamp} or a JPA auditing listener. Those fire on every save, including the
     * one that creates the row, so a comment would be born marked edited. Issues are the QA trail
     * and this flag is read as a statement about the record, so it has to mean what it says.
     *
     * <p>Two columns rather than a null check on one, matching {@code ChatMessage}, which has
     * carried {@code isEdited} and {@code editedAt} together since the chat module was built.
     */
    @Column(name = "is_edited", nullable = false)
    private boolean edited = false;

    /** When the author last changed the text, or null if they never have. */
    @Column(name = "edited_at")
    private LocalDateTime editedAt;
}
