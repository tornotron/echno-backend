package org.tornotron.echno_backend.project;

/**
 * Someone joined or left a project, published inside the transaction that made the change. Which
 * records a person may reach can depend on the projects they are on, so whatever keeps that answer
 * listens for this.
 *
 * @param organizationId the project's organization
 * @param employeeId     the employee who joined or left
 */
public record ProjectMembershipChanged(Long organizationId, Long employeeId) {
}
