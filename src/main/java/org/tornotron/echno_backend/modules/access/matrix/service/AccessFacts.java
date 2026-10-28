package org.tornotron.echno_backend.modules.access.matrix.service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.tornotron.echno_backend.modules.access.api.AccessScope;

/**
 * Everything the matrix engine needs to know about one person in one organization to answer any
 * question about them: whether they are an active member, what they hold, which projects they are
 * on, and which customers or vendors they represent.
 *
 * <p>Loaded once and reused for every decision in a request, so no decision costs a query.
 *
 * @param active      whether the person is an active member; an inactive one holds nothing
 * @param grants      each permission key mapped to the scopes held for it, already merged from
 *                    roles, groups and direct grants
 * @param projectIds  the projects the person is a member of
 * @param customerIds the customers the person represents
 * @param vendorIds   the vendors the person represents
 */
public record AccessFacts(
        boolean active,
        Map<String, Set<AccessScope>> grants,
        Set<Long> projectIds,
        Set<UUID> customerIds,
        Set<Long> vendorIds) {

    private static final AccessFacts NO_ACCESS = new AccessFacts(false, Map.of(), Set.of(), Set.of(), Set.of());

    public AccessFacts {
        grants = grants == null ? Map.of() : Map.copyOf(grants);
        projectIds = projectIds == null ? Set.of() : Set.copyOf(projectIds);
        customerIds = customerIds == null ? Set.of() : Set.copyOf(customerIds);
        vendorIds = vendorIds == null ? Set.of() : Set.copyOf(vendorIds);
    }

    /** A person who is not, or no longer, an active member: nothing is held. */
    public static AccessFacts noAccess() {
        return NO_ACCESS;
    }
}
