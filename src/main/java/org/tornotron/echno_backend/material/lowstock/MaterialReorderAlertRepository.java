package org.tornotron.echno_backend.material.lowstock;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The latch rows, read the two ways the sweep reads them.
 */
public interface MaterialReorderAlertRepository extends JpaRepository<MaterialReorderAlert, Long> {

    /**
     * Every material currently latched on one project.
     *
     * <p>Read whole rather than probed one material at a time: a pass already holds the project's
     * low-stock list in memory and has to answer two questions against these rows, which of the
     * low materials have already been reported and which of the reported ones are no longer low,
     * and the second question is about the rows the first one did not match.
     *
     * @param organizationId The tenant. Written out rather than left to {@code orgFilter},
     *         because the caller is a background pass that sets its own scope.
     * @param projectId The project.
     */
    List<MaterialReorderAlert> findByOrganization_IdAndProject_Id(Long organizationId, Long projectId);

    /**
     * How many notifications have been raised anywhere since a moment.
     *
     * <p>The per-pass cap is counted from the table rather than from a local variable, for the
     * reason {@code ComplianceRuleSweep} documents at length: {@code @Scheduled} elects no leader,
     * so a cap held in memory on N replicas is N caps. This makes it one budget they share. It is
     * a bound and not a lock, and the honest statement of what it buys is that the overshoot falls
     * from a whole cap per replica to roughly what one replica can send between two counts.
     *
     * <p>It counts across tenants deliberately: the cap is on the noise one pass may generate in
     * total, not per organization, because the thing being protected is the pass rather than any
     * one tenant's inbox.
     */
    long countByNotifiedAtGreaterThanEqual(LocalDateTime since);
}
