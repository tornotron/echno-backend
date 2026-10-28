package org.tornotron.echno_backend.modules.access.api;

import java.util.Set;
import java.util.UUID;

/**
 * Which records of an area a person may see, for a list.
 *
 * <p>A module turns this into a condition in its own query, so paging and counts stay right.
 * Filtering after loading would show a short page and a wrong total. The conditions are the same
 * facts a module puts in {@link RecordFacts}, so a record passes the filter exactly when
 * {@link AccessService#check} would allow it.
 *
 * <p>When {@link #everything()} is true the list is unrestricted. Otherwise a record is visible if
 * any one condition holds:
 * <ul>
 *   <li>its project is in {@link #projectIds()};
 *   <li>one of its owners is in {@link #ownerIds()};
 *   <li>one of its related people is in {@link #relatedIds()};
 *   <li>one of its customers is in {@link #customerIds()}, or one of its vendors is in
 *       {@link #vendorIds()}.
 * </ul>
 * With every set empty and {@code everything} false, nothing is visible.
 */
public record AccessFilter(
        boolean everything,
        Set<Long> projectIds,
        Set<Long> ownerIds,
        Set<Long> relatedIds,
        Set<UUID> customerIds,
        Set<Long> vendorIds) {

    private static final AccessFilter EVERYTHING = new AccessFilter(true, null, null, null, null, null);
    private static final AccessFilter NOTHING = new AccessFilter(false, null, null, null, null, null);

    public AccessFilter {
        projectIds = projectIds == null ? Set.of() : Set.copyOf(projectIds);
        ownerIds = ownerIds == null ? Set.of() : Set.copyOf(ownerIds);
        relatedIds = relatedIds == null ? Set.of() : Set.copyOf(relatedIds);
        customerIds = customerIds == null ? Set.of() : Set.copyOf(customerIds);
        vendorIds = vendorIds == null ? Set.of() : Set.copyOf(vendorIds);
    }

    public static AccessFilter all() {
        return EVERYTHING;
    }

    public static AccessFilter none() {
        return NOTHING;
    }

    /** True when no record can pass. */
    public boolean isEmpty() {
        return !everything && projectIds.isEmpty() && ownerIds.isEmpty() && relatedIds.isEmpty()
                && customerIds.isEmpty() && vendorIds.isEmpty();
    }

    /** Whether a record with these facts passes; the same answer a module's query gives. */
    public boolean admits(RecordFacts record) {
        if (everything) {
            return true;
        }
        return (record.projectId() != null && projectIds.contains(record.projectId()))
                || intersects(ownerIds, record.ownerIds())
                || intersects(relatedIds, record.relatedIds())
                || intersects(customerIds, record.customerIds())
                || intersects(vendorIds, record.vendorIds());
    }

    private static <T> boolean intersects(Set<T> allowed, Set<T> present) {
        for (T value : present) {
            if (allowed.contains(value)) {
                return true;
            }
        }
        return false;
    }
}
