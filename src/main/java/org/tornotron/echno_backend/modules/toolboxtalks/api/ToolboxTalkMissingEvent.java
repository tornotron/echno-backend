package org.tornotron.echno_backend.modules.toolboxtalks.api;

import java.time.LocalDate;

/**
 * Published by the daily reminder for each open project that had no talk recorded on the day
 * it looked at. A notification or chat hook subscribes here; the module itself only logs.
 */
public record ToolboxTalkMissingEvent(Long organizationId, Long projectId, String projectName, LocalDate day) {
}
