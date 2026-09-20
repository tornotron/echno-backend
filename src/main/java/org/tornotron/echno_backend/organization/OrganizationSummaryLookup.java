package org.tornotron.echno_backend.organization;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * The counts and the logo for a list of organizations, read once and handed to the mapper.
 *
 * <p>The organization cards render how many employees and projects each organization has and
 * its logo. The full {@link org.tornotron.echno_backend.organization.dto.OrganizationDto} answers
 * that by carrying every employee, every project and every attachment, and the client counts the
 * arrays and picks the logo out of the attachments. The summary asks the database for the counts
 * and the logo key instead, once for the whole list, resolves the key to a download URL the same
 * way the attachment mapper does, and passes the result down as a MapStruct {@code @Context} in
 * the shape {@link org.tornotron.echno_backend.inventoryTransaction.MaterialStockLookup}
 * established.
 */
public final class OrganizationSummaryLookup {

    private static final OrganizationSummaryLookup EMPTY =
            new OrganizationSummaryLookup(Map.of(), Map.of());

    private final Map<Long, OrganizationSummaryTotals> byOrganizationId;
    private final Map<Long, String> logoUrlByOrganizationId;

    private OrganizationSummaryLookup(Map<Long, OrganizationSummaryTotals> byOrganizationId,
                                      Map<Long, String> logoUrlByOrganizationId) {
        this.byOrganizationId = byOrganizationId;
        this.logoUrlByOrganizationId = logoUrlByOrganizationId;
    }

    /**
     * A lookup holding nothing, so every organization reads as zero of each count and no logo.
     *
     * @return The empty lookup.
     */
    public static OrganizationSummaryLookup none() {
        return EMPTY;
    }

    /**
     * Builds a lookup from the rows of the read, resolving each logo key to a URL.
     *
     * @param totals The per-organization rows, at most one per organization.
     * @param urlFor Turns a storage key into a download URL; called once per organization that
     *               has a logo.
     * @return A lookup over those rows.
     */
    public static OrganizationSummaryLookup of(Collection<OrganizationSummaryTotals> totals,
                                               Function<String, String> urlFor) {
        if (totals == null || totals.isEmpty()) {
            return EMPTY;
        }
        Map<Long, OrganizationSummaryTotals> rows = new HashMap<>();
        Map<Long, String> urls = new HashMap<>();
        for (OrganizationSummaryTotals row : totals) {
            if (row.organizationId() == null || rows.containsKey(row.organizationId())) {
                continue;
            }
            rows.put(row.organizationId(), row);
            if (row.logoStorageKey() != null) {
                urls.put(row.organizationId(), urlFor.apply(row.logoStorageKey()));
            }
        }
        return new OrganizationSummaryLookup(Map.copyOf(rows), Map.copyOf(urls));
    }

    /**
     * How many employees an organization has.
     *
     * @param organizationId The organization to read.
     * @return The count, zero where the organization is absent from the lookup.
     */
    public long employeeCountOf(Long organizationId) {
        OrganizationSummaryTotals row = byOrganizationId.get(organizationId);
        return row == null ? 0L : row.employeeCount();
    }

    /**
     * How many projects an organization has.
     *
     * @param organizationId The organization to read.
     * @return The count, zero where the organization is absent from the lookup.
     */
    public long projectCountOf(Long organizationId) {
        OrganizationSummaryTotals row = byOrganizationId.get(organizationId);
        return row == null ? 0L : row.projectCount();
    }

    /**
     * The download URL of an organization's logo.
     *
     * @param organizationId The organization to read.
     * @return The URL, or null where the organization has no logo attachment.
     */
    public String logoUrlOf(Long organizationId) {
        return logoUrlByOrganizationId.get(organizationId);
    }

    /**
     * Whether the lookup holds no rows at all.
     *
     * @return {@code true} when every organization reads as zero and no logo.
     */
    public boolean isEmpty() {
        return byOrganizationId.isEmpty();
    }
}
