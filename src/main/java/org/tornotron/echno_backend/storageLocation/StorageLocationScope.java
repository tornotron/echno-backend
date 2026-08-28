package org.tornotron.echno_backend.storageLocation;

import org.tornotron.echno_backend.common.exception.InvalidRequestException;

/**
 * The rule deciding which projects a storage location may be booked against.
 *
 * <p><strong>A location with no project is an organisation-level store</strong>: a central
 * yard or warehouse that every project draws from, so it is selectable from all of them.
 * A location that names a project belongs to that project alone and may not be used from
 * any other. Where such a row ought to be owned by a project, the fix is to populate its
 * project, not to make the row unusable.
 *
 * <p>Nothing used to check this at all, so a consumption could be booked against a
 * (project, location) pair whose stock row could never exist, and the request was refused
 * with a bare "Available: 0.00". Every path that books a movement against a project and a
 * location goes through {@link #requireUsableFromProject} so the rule is stated once.
 *
 * <p>The web client applies the same rule when it fills the location dropdown
 * ({@code features/material-consumptions/storage-location-scope.ts}), so a change here
 * has to be made there too.
 */
public final class StorageLocationScope {

    private StorageLocationScope() {
    }

    /**
     * Says whether a location may hold stock for the given project.
     *
     * @param location The storage location, or null when no location is named.
     * @param projectId The project the movement is booked against.
     * @return True when the location is organisation-level or belongs to this project.
     */
    public static boolean isUsableFromProject(StorageLocation location, Long projectId) {
        if (location == null || location.getProject() == null) {
            return true;
        }
        return location.getProject().getId().equals(projectId);
    }

    /**
     * Refuses a location that belongs to a different project.
     *
     * @param location The storage location, or null when no location is named.
     * @param projectId The project the movement is booked against.
     * @throws InvalidRequestException if the location belongs to another project.
     */
    public static void requireUsableFromProject(StorageLocation location, Long projectId) {
        if (isUsableFromProject(location, projectId)) {
            return;
        }
        throw new InvalidRequestException(String.format(
                "Storage location with ID %d belongs to project with ID %d and cannot be used from "
                        + "project with ID %d. Choose a location on this project, or an "
                        + "organisation-level location, which belongs to no project and is available "
                        + "from every one.",
                location.getId(), location.getProject().getId(), projectId));
    }
}
