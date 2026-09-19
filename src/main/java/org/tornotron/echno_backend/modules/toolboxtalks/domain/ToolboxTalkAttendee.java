package org.tornotron.echno_backend.modules.toolboxtalks.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One employee who attended a talk. An owned child of {@link ToolboxTalk}: it has no
 * organization column of its own and cannot exist without its talk, which is what the
 * non-nullable association says and what {@code TenantScopedJoinTest} checks.
 */
@Entity
@Table(name = "toolbox_talk_attendee",
        uniqueConstraints = @UniqueConstraint(name = "uq_toolbox_talk_attendee",
                columnNames = {"talk_id", "employee_id"}))
@Getter
@Setter
@NoArgsConstructor
public class ToolboxTalkAttendee {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "talk_id", nullable = false)
    private ToolboxTalk talk;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;
}
