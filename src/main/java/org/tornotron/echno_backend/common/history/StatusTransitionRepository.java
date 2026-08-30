package org.tornotron.echno_backend.common.history;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/**
 * Reads and appends to the shared status trail.
 *
 * <p>This extends {@link Repository} rather than {@code JpaRepository} on purpose. Spring Data
 * implements only the methods a repository declares, so the trail has no {@code delete},
 * {@code deleteAll} or bulk write surface for anything to reach by accident. Append-only is a
 * property of the type here rather than a convention somebody has to remember, and
 * {@link StatusTransition} is {@code @Immutable} on top of that, so an entry cannot be edited
 * through a loaded instance either. It is the same shape as {@code AssetMovementRepository}.
 *
 * <p>Every read names the organization. The trail carries no foreign key to the record it
 * describes, so the organization column is the whole of its tenant scope: a finder by
 * {@code entityId} alone would cross tenants the moment two organizations held the same id.
 */
public interface StatusTransitionRepository extends Repository<StatusTransition, Long> {

    /** Appends an entry. The only write this repository offers. */
    StatusTransition save(StatusTransition transition);

    /**
     * One record's trail, newest first.
     *
     * <p>Newest first because the head of the trail is the entry that explains the status the
     * record is in now, which is what a reader has asked about; a capped read from the oldest end
     * would leave that entry off the page. The page carries the whole trail's size, so a
     * shortened read can say it was shortened.
     */
    Page<StatusTransition> findByEntityTypeAndEntityIdAndOrganization_IdOrderByOccurredAtDescIdDesc(
            String entityType, Long entityId, Long organizationId, Pageable pageable);

    /** How many entries a record carries, for callers that only need to know whether any exist. */
    long countByEntityTypeAndEntityIdAndOrganization_Id(
            String entityType, Long entityId, Long organizationId);
}
