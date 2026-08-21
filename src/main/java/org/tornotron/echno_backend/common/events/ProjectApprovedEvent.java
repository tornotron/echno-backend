package org.tornotron.echno_backend.common.events;

import org.springframework.context.ApplicationEvent;

/**
 * Published when a project transitions into the {@code approved} status. Carries
 * the project id and its organization id explicitly: the compliance-generation
 * listener runs on a separate async thread that has no {@code TenantContext}, so
 * it restores the tenant from the event rather than from thread-local state.
 */
public class ProjectApprovedEvent extends ApplicationEvent {

    private final Long projectId;
    private final Long organizationId;

    public ProjectApprovedEvent(Object source, Long projectId, Long organizationId) {
        super(source);
        this.projectId = projectId;
        this.organizationId = organizationId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getOrganizationId() {
        return organizationId;
    }
}
