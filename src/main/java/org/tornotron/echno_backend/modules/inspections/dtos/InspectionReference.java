package org.tornotron.echno_backend.modules.inspections.dtos;

import java.util.UUID;

/**
 * The few fields of an inspection that another record needs in order to say where it came
 * from: enough to name the inspection and the project it belongs to, without loading the
 * inspection's check points and defects.
 *
 * <p>Read in bulk by {@code InspectionRepository#findReferencesByIdsScoped} for a page of NCRs,
 * and taken off the loaded parent for a defect.
 *
 * @param id The inspection.
 * @param inspectionNumber Its document number.
 * @param title Its title.
 * @param projectId The project it belongs to, or null on an inspection recorded without one.
 */
public record InspectionReference(UUID id, String inspectionNumber, String title, Long projectId) {
}
