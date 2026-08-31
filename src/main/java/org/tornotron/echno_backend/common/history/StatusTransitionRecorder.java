package org.tornotron.echno_backend.common.history;

import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.user.User;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Writes entries to the shared status trail.
 *
 * <p>The single writer for every module that records a status transition. Callers hand it the
 * record's kind and id, the organization the record belongs to, the two statuses and the acting
 * user; it decides nothing about the domain and reads nothing back.
 *
 * <p>The organization comes from the record being changed and never from a request field, so a
 * caller cannot file an entry into another tenant's trail.
 */
@Service
public class StatusTransitionRecorder {

    private final StatusTransitionRepository repository;

    public StatusTransitionRecorder(StatusTransitionRepository repository) {
        this.repository = repository;
    }

    /**
     * Records that a record was created holding a status.
     *
     * @param entityType   The kind of record, for example {@code PROJECT}.
     * @param entityId     The record's id.
     * @param organization The organization the record belongs to.
     * @param status       The status it was created in.
     * @param actor        The acting user, or null where there was no user context.
     * @return The entry that was written.
     */
    public StatusTransition recordCreation(String entityType, Long entityId,
                                           Organization organization, String status, User actor) {
        return append(entityType, entityId, organization, null, status,
                StatusTransitionSource.CREATION, actor, null);
    }

    /**
     * Records a change of status on an existing record, if it is one.
     *
     * <p>Returns null and writes nothing when the two statuses are equal, so a caller can call
     * this on every save without first working out whether the status moved. A save that leaves
     * the status where it was is not a transition and must not appear in the trail as one.
     *
     * @param entityType   The kind of record, for example {@code PROJECT}.
     * @param entityId     The record's id.
     * @param organization The organization the record belongs to.
     * @param fromStatus   The status held before the change, possibly null.
     * @param toStatus     The status held after it.
     * @param actor        The acting user, or null where there was no user context.
     * @param note         Anything worth saying beyond the two statuses, or null.
     * @return The entry that was written, or null when nothing changed.
     */
    public StatusTransition recordChange(String entityType, Long entityId,
                                         Organization organization, String fromStatus,
                                         String toStatus, User actor, String note) {
        if (Objects.equals(fromStatus, toStatus)) {
            return null;
        }
        return append(entityType, entityId, organization, fromStatus, toStatus,
                StatusTransitionSource.UPDATE, actor, note);
    }

    /**
     * Records a change of status the application worked out for itself, if it is one.
     *
     * <p>The same rule as {@link #recordChange}: nothing is written when the two statuses are
     * equal. What differs is that there is no acting user and none is invented. The entry is
     * sourced {@link StatusTransitionSource#SYSTEM} and the note is where the caller names the
     * document whose arithmetic moved the status, so a reader can follow it to the person who
     * filed that document.
     *
     * @param entityType   The kind of record, for example {@code PURCHASE_ORDER}.
     * @param entityId     The record's id.
     * @param organization The organization the record belongs to.
     * @param fromStatus   The status held before the change, possibly null.
     * @param toStatus     The status held after it.
     * @param note         The document or rule that moved it. Give one; an unexplained system
     *                     transition is the thing this method exists to avoid.
     * @return The entry that was written, or null when nothing changed.
     */
    public StatusTransition recordSystemChange(String entityType, Long entityId,
                                               Organization organization, String fromStatus,
                                               String toStatus, String note) {
        if (Objects.equals(fromStatus, toStatus)) {
            return null;
        }
        return append(entityType, entityId, organization, fromStatus, toStatus,
                StatusTransitionSource.SYSTEM, null, note);
    }

    private StatusTransition append(String entityType, Long entityId, Organization organization,
                                    String fromStatus, String toStatus,
                                    StatusTransitionSource source, User actor, String note) {
        StatusTransition transition = new StatusTransition();
        transition.setEntityType(entityType);
        transition.setEntityId(entityId);
        transition.setOrganization(organization);
        transition.setFromStatus(fromStatus);
        transition.setToStatus(toStatus);
        transition.setSource(source);
        transition.setOccurredAt(LocalDateTime.now());
        transition.setChangedBy(actor != null ? actor.getId() : null);
        transition.setChangedByName(actor != null ? actor.getName() : null);
        transition.setNote(note);
        return repository.save(transition);
    }
}
