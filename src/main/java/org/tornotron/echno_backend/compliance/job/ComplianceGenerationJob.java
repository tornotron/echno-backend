package org.tornotron.echno_backend.compliance.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One request to generate compliance inspections for a project, held durably so the work
 * can happen after the request that asked for it has been answered.
 *
 * <h2>Why the work needs a row at all</h2>
 *
 * <p>The model call runs at roughly two seconds a rule and the whole catalogue is the unit
 * of work, so a request that waits for it dies at the edge timeout once a jurisdiction has
 * a few dozen rules. Handing the work to an {@code @Async} method would move the wait off
 * the request thread but would leave it in a thread pool that a restart empties without a
 * trace: the user would be told the work had started and nothing would ever finish it. The
 * row is what makes the promise keepable. It also gives the caller something to read while
 * the work runs, which an in-memory future does not, since the replica answering the poll
 * is not necessarily the one doing the work.
 *
 * <h2>One active job per project</h2>
 *
 * <p>{@code (organization_id, project_id)} is unique over the rows in a non-terminal state
 * (changelog {@code 062}), so a second click while a run is in flight cannot start a second
 * run. The insert is rejected and the submitting code hands back the job already running,
 * which is what the user meant. That closes the same duplicate-inspection race the
 * inspection-level unique index closes, one step earlier and without the losing run having
 * to do the whole model call first to find out.
 *
 * <h2>Claiming, and what happens when a worker dies</h2>
 *
 * <p>There is no queue broker. Every replica polls, and a claim is a conditional update
 * ({@code SET status='RUNNING' WHERE id=? AND status='QUEUED'}) which exactly one replica
 * can win. The winner writes {@link #leaseExpiresAt} ahead of itself and pushes it forward
 * as it finishes each batch. A replica that dies stops pushing, the lease goes stale, and
 * the next poll on any replica puts the row back to {@code QUEUED}. Re-running is safe
 * because generation is idempotent per rule, so the recovered attempt creates only what the
 * dead one had not got to.
 *
 * <h2>Progress</h2>
 *
 * <p>{@link #batchesDone} / {@link #batchesTotal} and {@link #rulesAssessed} /
 * {@link #rulesTotal} are written after each batch, in their own short transaction, so the
 * polling caller sees the run advance rather than a spinner with nothing behind it. They
 * are progress, not a result: a run that stops at batch three of five has assessed some
 * rules and produced nothing, and its status says {@code FAILED} rather than reporting the
 * rules it did get through as though they were the answer.
 */
@Entity
@Table(name = "compliance_generation_jobs",
        indexes = {
                @Index(name = "idx_compliance_job_project", columnList = "organization_id, project_id"),
                @Index(name = "idx_compliance_job_status", columnList = "status")
        })
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class ComplianceGenerationJob implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    /** Scalar project reference, as inspections themselves carry. */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private ComplianceJobStatus status = ComplianceJobStatus.QUEUED;

    /** Candidate rules for the project's jurisdiction, counted when the job was accepted. */
    @Column(name = "rules_total", nullable = false)
    private int rulesTotal;

    /** Rules the model has come back on so far. Only ever moves forward within an attempt. */
    @Column(name = "rules_assessed", nullable = false)
    private int rulesAssessed;

    /** How many model calls the run is split into, from the configured batch size. */
    @Column(name = "batches_total", nullable = false)
    private int batchesTotal;

    @Column(name = "batches_done", nullable = false)
    private int batchesDone;

    /** Compliance inspections this run created. Zero is a real answer; see {@link ComplianceJobStatus}. */
    @Column(name = "created_count", nullable = false)
    private int createdCount;

    /**
     * Why the last attempt did not finish, in the words the user should read. Set while the
     * job is still being retried as well as on final failure, so a caller polling through a
     * retry is told what is going wrong rather than watching an unexplained pause.
     */
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    /** Attempts started, incremented by the claim itself so a crash still counts. */
    @Column(name = "attempt", nullable = false)
    private int attempt;

    /** Copied from configuration at accept time so a config change cannot strand a running job. */
    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    /**
     * When the current claim goes stale. Null while queued. A worker pushes it forward after
     * each batch; a lease in the past means the worker holding it is gone.
     */
    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    /** Which replica holds the claim. Diagnostic only: correctness rests on the conditional update. */
    @Column(name = "claimed_by", length = 120)
    private String claimedBy;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
