package org.tornotron.echno_backend.inspection.repositories;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.tornotron.echno_backend.inspection.NcrStatus;
import org.tornotron.echno_backend.inspection.NcrType;
import org.tornotron.echno_backend.inspection.domain.Ncr;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds the optional list filters as a JPA {@link Specification}. Only non-null
 * arguments become predicates, which avoids binding null-typed parameters on the
 * Postgres wire protocol. Organization scoping is handled separately by the
 * Hibernate {@code orgFilter} enabled per request.
 */
public final class NcrSpecifications {

    private NcrSpecifications() {}

    public static Specification<Ncr> withFilters(UUID inspectionId,
                                                 NcrType type,
                                                 NcrStatus status,
                                                 Long siteEngineerId,
                                                 Long raisedById,
                                                 Long verifiedById,
                                                 Long closedById,
                                                 Boolean open) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (inspectionId != null) {
                predicates.add(cb.equal(root.get("inspectionId"), inspectionId));
            }
            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (siteEngineerId != null) {
                predicates.add(cb.equal(root.get("siteEngineerId"), siteEngineerId));
            }
            // The three people the accountability trail names, each an employee id of
            // this tenant. They narrow within the tenant and never past it: the org
            // scope is a separate condition the filter adds to every query, so an id
            // belonging to somebody in another organization matches no row here rather
            // than reaching their reports.
            if (raisedById != null) {
                predicates.add(cb.equal(root.get("raisedById"), raisedById));
            }
            if (verifiedById != null) {
                predicates.add(cb.equal(root.get("verifiedById"), verifiedById));
            }
            if (closedById != null) {
                predicates.add(cb.equal(root.get("closedById"), closedById));
            }
            if (open != null) {
                // The punch list: everything still outstanding, which is every NCR that
                // has not been closed. Expressed as one flag rather than making a client
                // enumerate six statuses and get it wrong when a seventh is added.
                Predicate notClosed = cb.notEqual(root.get("status"), NcrStatus.CLOSED);
                predicates.add(open ? notClosed : cb.equal(root.get("status"), NcrStatus.CLOSED));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
