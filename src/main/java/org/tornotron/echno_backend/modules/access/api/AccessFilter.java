package org.tornotron.echno_backend.modules.access.api;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Which records of an area a person may see in one organization, for a list.
 *
 * <p>A module turns this into a condition in its own query, so paging and counts stay right.
 * Filtering after loading would show a short page and a wrong total. The conditions are the same
 * facts a module puts in {@link RecordFacts}, so a record passes the filter exactly when
 * {@link AccessService#check} would allow it.
 *
 * <p>A record of any other organization never passes. Inside the organization, when
 * {@link #everything()} is true the list is unrestricted. Otherwise a record is visible if any one
 * condition holds:
 * <ul>
 *   <li>its project is in {@link #projectIds()};
 *   <li>it matches {@link #links()};
 *   <li>its project has an entry in {@link #withinProject()} and it matches that entry, which is
 *       how a grant held on one project stays inside that project.
 * </ul>
 * With nothing set and {@code everything} false, nothing is visible.
 *
 * @param organizationId the organization the filter belongs to
 * @param everything     whether every record of the organization passes
 * @param projectIds     projects all of whose records pass
 * @param links          links that admit a record wherever it sits
 * @param withinProject  links that admit a record only on the project they are keyed by
 */
public record AccessFilter(
        Long organizationId,
        boolean everything,
        Set<Long> projectIds,
        Links links,
        Map<Long, Links> withinProject) {

    public AccessFilter {
        Objects.requireNonNull(organizationId, "organizationId");
        projectIds = projectIds == null ? Set.of() : Set.copyOf(projectIds);
        links = links == null ? Links.NONE : links;
        withinProject = withinProject == null ? Map.of() : withinProject.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Every record of the organization. */
    public static AccessFilter all(Long organizationId) {
        return new AccessFilter(organizationId, true, null, null, null);
    }

    /** No record at all. */
    public static AccessFilter none(Long organizationId) {
        return new AccessFilter(organizationId, false, null, null, null);
    }

    /** True when no record can pass. */
    public boolean isEmpty() {
        return !everything && projectIds.isEmpty() && links.isEmpty() && withinProject.isEmpty();
    }

    /** Whether a record with these facts passes; the same answer a module's query gives. */
    public boolean admits(RecordFacts record) {
        if (!organizationId.equals(record.organizationId())) {
            return false;
        }
        if (everything) {
            return true;
        }
        Long project = record.projectId();
        if (project != null && projectIds.contains(project)) {
            return true;
        }
        if (links.admit(record)) {
            return true;
        }
        Links onProject = project == null ? null : withinProject.get(project);
        return onProject != null && onProject.admit(record);
    }

    /**
     * The links that connect a person to a record. A record matches when any one of its owners,
     * related people, customers or vendors is listed here.
     */
    public record Links(Set<Long> ownerIds, Set<Long> relatedIds, Set<UUID> customerIds, Set<Long> vendorIds) {

        public static final Links NONE = new Links(null, null, null, null);

        public Links {
            ownerIds = ownerIds == null ? Set.of() : Set.copyOf(ownerIds);
            relatedIds = relatedIds == null ? Set.of() : Set.copyOf(relatedIds);
            customerIds = customerIds == null ? Set.of() : Set.copyOf(customerIds);
            vendorIds = vendorIds == null ? Set.of() : Set.copyOf(vendorIds);
        }

        public boolean isEmpty() {
            return ownerIds.isEmpty() && relatedIds.isEmpty() && customerIds.isEmpty() && vendorIds.isEmpty();
        }

        boolean admit(RecordFacts record) {
            return intersects(ownerIds, record.ownerIds())
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
}
