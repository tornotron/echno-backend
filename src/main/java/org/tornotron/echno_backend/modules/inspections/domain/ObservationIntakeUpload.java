package org.tornotron.echno_backend.modules.inspections.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

/**
 * One presign call on the producer upload path. Its id is the {@code uploadId} the producer sends
 * back to register the files, and the attachments are filed against it. The principal is held here
 * because intake has to tell one producer's upload from another's, and a service account need not
 * have a row in the users table for the attachment to point at.
 */
@Entity
@Table(name = "inspection_observation_intake_uploads")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class ObservationIntakeUpload implements TenantScopedEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** The caller's token subject. */
    @Column(name = "producer_subject", nullable = false)
    private String producerSubject;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
