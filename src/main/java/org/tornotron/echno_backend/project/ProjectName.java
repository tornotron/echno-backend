package org.tornotron.echno_backend.project;

/**
 * A project's id and display name, as returned by {@link ProjectRepository#findNamesByIds}, for
 * the places that label a row with the project it belongs to and want nothing else of it.
 *
 * @param id The project.
 * @param projectName Its display name.
 */
public record ProjectName(Long id, String projectName) {
}
