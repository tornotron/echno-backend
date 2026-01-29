package org.tornotron.echno_backend.issue;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.IssueComment.IssueComment;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.issue.enums.IssueStatus;
import org.tornotron.echno_backend.issue.enums.IssueType;
import org.tornotron.echno_backend.task.Task;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Data
@NoArgsConstructor
public class Issue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", nullable = false, unique = true)
    private String title;

    @Column(name = "description", nullable = true)
    private String description;

    @Column(name = "type", nullable = false)
    @Enumerated(EnumType.STRING)
    private IssueType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private IssueStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private String creator;

    @OneToMany(mappedBy = "issue")
    @Column(nullable = true)
    private List<IssueComment> issueComments;

    @ManyToOne
    private Task task;

    /** The list of attachments associated with this issue. */
    @OneToMany(mappedBy = "issue")
    private List<Attachment> attachments;
}
