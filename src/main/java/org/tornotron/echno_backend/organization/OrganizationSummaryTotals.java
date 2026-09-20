package org.tornotron.echno_backend.organization;

/**
 * What an organization card renders beyond the organization's own columns, as returned by the
 * read in {@link OrganizationRepository#summaryTotalsByOrganizationIds}: how many employees and
 * projects it has, and the storage key of its current logo.
 *
 * <p>One row comes back for every organization asked for. The counts are never null: an
 * organization with nothing to count reads as zero. {@code logoStorageKey} is null where no
 * {@code ORGANIZATION_LOGO} attachment exists; where several do, it is the most recently created
 * one, which is the one the full view's client picks out of the attachment list.
 *
 * @param organizationId The organization these totals belong to.
 * @param employeeCount How many employees it has.
 * @param projectCount How many projects it has.
 * @param logoStorageKey The storage key of its latest logo attachment, or null.
 */
public record OrganizationSummaryTotals(Long organizationId, long employeeCount, long projectCount,
                                        String logoStorageKey) {

    /**
     * Builds a row from the columns of the native read, in the order the query selects them.
     *
     * @param row The four selected columns: id, employee count, project count, logo key.
     * @return The totals row.
     */
    public static OrganizationSummaryTotals fromRow(Object[] row) {
        return new OrganizationSummaryTotals(
                ((Number) row[0]).longValue(),
                ((Number) row[1]).longValue(),
                ((Number) row[2]).longValue(),
                (String) row[3]);
    }
}
