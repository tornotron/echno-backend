package org.tornotron.echno_backend.modules.access.api;

import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * Appends to an organization's access history, for a change made outside this module that the
 * history has to show, such as an invitation holding roles. The history is append-only.
 */
public interface AccessChanges {

    /**
     * Records one change. Joins the caller's transaction.
     *
     * @param actorUserId the Echno user who made it, or null when the system did.
     */
    void record(Long organizationId, @Nullable Long actorUserId, String action, String targetType, String targetId,
                @Nullable Map<String, Object> before, @Nullable Map<String, Object> after);
}
