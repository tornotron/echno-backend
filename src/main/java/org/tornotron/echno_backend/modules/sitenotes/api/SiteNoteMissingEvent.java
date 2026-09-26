package org.tornotron.echno_backend.modules.sitenotes.api;

import java.time.LocalDate;

/**
 * Published by the daily reminder for each open project that had no site note on the day it
 * looked at. A notification hook subscribes here; the module itself only logs.
 */
public record SiteNoteMissingEvent(Long organizationId, Long projectId, String projectName, LocalDate day) {
}
