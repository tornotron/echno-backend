package org.tornotron.echno_backend.modules.inspections.api;

/**
 * Published once per image when inspection or observation evidence is stored, inside the storing
 * transaction. A listener that acts on it should do so after commit, so a rolled-back upload
 * triggers nothing. Files that are not images publish nothing.
 *
 * @param file the stored image
 */
public record EvidenceImageRegistered(EvidenceFile file) {}
