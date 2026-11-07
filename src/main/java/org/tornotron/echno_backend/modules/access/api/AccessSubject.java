package org.tornotron.echno_backend.modules.access.api;

import java.util.Objects;

/**
 * The person a decision is about, inside one organization.
 *
 * <p>Access is held by a membership, not by a login: the same user may be a project manager in
 * one organization and a client in another. So a subject is the organization plus the employee
 * record that is the user's membership there. The user id is carried as well because some
 * records name their owner by user rather than by employee.
 *
 * @param organizationId the organization the decision is made in
 * @param employeeId     the person's membership in that organization
 * @param userId         the person's login; may be null where it is not known
 */
public record AccessSubject(Long organizationId, Long employeeId, Long userId) {

    public AccessSubject {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(employeeId, "employeeId");
    }
}
