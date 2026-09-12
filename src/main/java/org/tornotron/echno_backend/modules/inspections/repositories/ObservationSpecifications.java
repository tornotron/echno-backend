package org.tornotron.echno_backend.modules.inspections.repositories;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import org.tornotron.echno_backend.modules.inspections.ObservationReviewStatus;
import org.tornotron.echno_backend.modules.inspections.ObservationSource;
import org.tornotron.echno_backend.modules.inspections.domain.Observation;
import org.tornotron.echno_backend.project.spatial.SpatialNode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Filters for the observation list. Same subtree mechanics as {@link InspectionSpecifications}. */
public final class ObservationSpecifications {

    private ObservationSpecifications() {}

    public static Specification<Observation> withFilters(Long projectId,
                                                         ObservationReviewStatus reviewStatus,
                                                         ObservationSource source,
                                                         UUID inspectionId,
                                                         String spatialPathPrefix,
                                                         LocalDateTime from,
                                                         LocalDateTime to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (InspectionSpecifications.NO_MATCH.equals(spatialPathPrefix)) {
                predicates.add(cb.disjunction());
            } else if (spatialPathPrefix != null) {
                Subquery<UUID> subtree = query.subquery(UUID.class);
                Root<SpatialNode> node = subtree.from(SpatialNode.class);
                subtree.select(node.get("id"))
                        .where(cb.like(node.get("path"), spatialPathPrefix + "%"));
                predicates.add(root.get("spatialNodeId").in(subtree));
            }
            if (projectId != null) {
                predicates.add(cb.equal(root.get("projectId"), projectId));
            }
            if (reviewStatus != null) {
                predicates.add(cb.equal(root.get("reviewStatus"), reviewStatus));
            }
            if (source != null) {
                predicates.add(cb.equal(root.get("source"), source));
            }
            if (inspectionId != null) {
                predicates.add(cb.equal(root.get("inspectionId"), inspectionId));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("observedAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("observedAt"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
