package org.tornotron.echno_backend.project.enums;

/**
 * Broad category of construction a project is. Together with the project's state
 * (derived from its address) this drives which statutory compliances the AI
 * generation flow considers, so the constant set matches the compliance rules
 * dataset. Stored by {@code @Enumerated(STRING)}.
 */
public enum ProjectType {
    RESIDENTIAL,
    COMMERCIAL,
    INDUSTRIAL,
    INFRASTRUCTURE,
    INSTITUTIONAL,
    MIXED_USE,
    OTHER
}
