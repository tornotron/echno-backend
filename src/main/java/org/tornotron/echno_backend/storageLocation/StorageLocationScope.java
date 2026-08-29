package org.tornotron.echno_backend.storageLocation;

import org.tornotron.echno_backend.common.exception.InvalidRequestException;

import java.util.function.BooleanSupplier;

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
 *
 * <p><strong>Correcting an existing balance is the one exception</strong>, and it has its
 * own entry point, {@link #requireUsableForBalanceCorrection}. The strict rule is written
 * for a <em>new</em> movement, where a wrong (project, location) pairing is a mistake being
 * made. A stock adjustment correcting a balance that already sits on such a pairing is the
 * one case where the wrong pairing is the thing being fixed, so refusing it removes the
 * only tool. That entry point accepts a location the balance row already exists at, and
 * nothing else: it permits correcting a pairing that is already there and still refuses
 * inventing a new one.
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

    /**
     * Refuses a location that belongs to a different project, unless a balance already sits there.
     *
     * <p>This is the rule for the stock-adjustment path alone, which is why it is a separate
     * method rather than a flag on {@link #requireUsableFromProject}: the strict rule stays
     * the default everywhere, and relaxing it has to be asked for by name. The relaxation is
     * deliberately narrow. It is not "any location in the organization": the location must
     * already hold a balance row for this material and project, which is exactly the pairing
     * an adjustment exists to correct. A location holding nothing is still refused, so an
     * adjustment cannot invent a cross-project pairing that was never there.
     *
     * @param location The storage location, or null when no location is named.
     * @param projectId The project the adjustment is booked against.
     * @param balanceRowExists Whether a balance row already exists for this material, project and
     *                         location. Consulted only when the strict rule would refuse, so the
     *                         caller can pass a lookup without paying for it on the common path.
     * @throws InvalidRequestException if the location belongs to another project and holds no balance.
     */
    public static void requireUsableForBalanceCorrection(StorageLocation location, Long projectId,
                                                         BooleanSupplier balanceRowExists) {
        if (isUsableFromProject(location, projectId) || balanceRowExists.getAsBoolean()) {
            return;
        }
        throw new InvalidRequestException(String.format(
                "Storage location with ID %d belongs to project with ID %d and holds no balance for "
                        + "this material on project with ID %d, so there is no figure here to correct. "
                        + "An adjustment may correct a balance that already sits on a location owned by "
                        + "another project, but it cannot create one. Choose a location this project "
                        + "already holds this material at, a location on this project, or an "
                        + "organisation-level location, which belongs to no project and is available "
                        + "from every one.",
                location.getId(), location.getProject().getId(), projectId));
    }
}
