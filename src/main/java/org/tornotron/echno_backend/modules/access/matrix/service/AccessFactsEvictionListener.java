package org.tornotron.echno_backend.modules.access.matrix.service;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.project.ProjectMembershipChanged;

/**
 * Drops a person's cached facts when something outside this module changes them. Joining or
 * leaving a project changes which records an assigned-projects grant reaches.
 */
@Component
class AccessFactsEvictionListener {

    private final CachedAccessFactsSource facts;

    AccessFactsEvictionListener(CachedAccessFactsSource facts) {
        this.facts = facts;
    }

    @EventListener
    void onProjectMembershipChanged(ProjectMembershipChanged event) {
        facts.evictAfterCommit(event.organizationId(), event.employeeId());
    }
}
