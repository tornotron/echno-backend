package org.tornotron.echno_backend.modules.access.api;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What a record is linked to, described by the module that owns it.
 *
 * <p>A module states facts and never rules: which organization and project the record belongs
 * to, who it is about, who it is assigned to, and which customer or vendor it carries. The engine
 * decides what those facts mean for a given person, so the same facts serve any engine.
 *
 * <p>Ids name employees, the person's membership in the organization, not users. A module whose
 * records hold a user id resolves it before describing the record.
 *
 * @param organizationId the organization the record belongs to
 * @param projectId      the project it sits on; null for a record outside any project
 * @param ownerIds       the employees the record is about, for {@link AccessScope#OWN}: the
 *                       person on a leave request, the employee whose attendance it is
 * @param relatedIds     the employees it is assigned to or otherwise linked with, for
 *                       {@link AccessScope#RELATED}: a task's assignees, an inspection's inspector
 * @param customerIds    the customers on it, for {@link AccessScope#RELATED} through a client link
 * @param vendorIds      the vendors on it, for {@link AccessScope#RELATED} through a vendor link
 */
public record RecordFacts(
        Long organizationId,
        Long projectId,
        Set<Long> ownerIds,
        Set<Long> relatedIds,
        Set<UUID> customerIds,
        Set<Long> vendorIds) {

    public RecordFacts {
        Objects.requireNonNull(organizationId, "organizationId");
        ownerIds = ownerIds == null ? Set.of() : Set.copyOf(ownerIds);
        relatedIds = relatedIds == null ? Set.of() : Set.copyOf(relatedIds);
        customerIds = customerIds == null ? Set.of() : Set.copyOf(customerIds);
        vendorIds = vendorIds == null ? Set.of() : Set.copyOf(vendorIds);
    }

    /** A record with no links beyond its organization and, optionally, its project. */
    public static RecordFacts of(Long organizationId, Long projectId) {
        return new RecordFacts(organizationId, projectId, null, null, null, null);
    }

    public RecordFacts withOwners(Set<Long> owners) {
        return new RecordFacts(organizationId, projectId, owners, relatedIds, customerIds, vendorIds);
    }

    public RecordFacts withRelated(Set<Long> related) {
        return new RecordFacts(organizationId, projectId, ownerIds, related, customerIds, vendorIds);
    }

    public RecordFacts withCustomers(Set<UUID> customers) {
        return new RecordFacts(organizationId, projectId, ownerIds, relatedIds, customers, vendorIds);
    }

    public RecordFacts withVendors(Set<Long> vendors) {
        return new RecordFacts(organizationId, projectId, ownerIds, relatedIds, customerIds, vendors);
    }
}
