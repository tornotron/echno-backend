package org.tornotron.echno_backend.issue.dto;

import java.util.Map;

/**
 * Aggregate issue counts for the issues dashboard, computed server-side over
 * the active filters (project / search / type) so the stats stay accurate when
 * the table is server-paginated.
 *
 * @param total    total matching issues across every status
 * @param byStatus count per status, keyed by the {@code IssueStatus} name
 *                 (e.g. {@code "open"}, {@code "inProgress"}); statuses with no
 *                 matches are omitted, so consumers should default absent keys
 *                 to zero
 */
public record IssueStatsDto(long total, Map<String, Long> byStatus) {
}
