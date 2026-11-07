package org.tornotron.echno_backend.modules.inspections.repositories;

import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import org.tornotron.echno_backend.modules.inspections.ObservationReviewStatus;
import org.tornotron.echno_backend.modules.inspections.ObservationSource;
import org.tornotron.echno_backend.modules.inspections.RegionOrigin;
import org.tornotron.echno_backend.modules.inspections.domain.Observation;
import org.tornotron.echno_backend.modules.inspections.domain.ObservationRegion;
import org.tornotron.echno_backend.project.spatial.SpatialNode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Filters for the observation list. Same subtree mechanics as {@link InspectionSpecifications}. */
public final class ObservationSpecifications {

    private ObservationSpecifications() {}

    /**
     * A model's detection: a finding posted through intake as AI, or one carrying a producer's region
     * whatever its source. The compliance model's suggestions have no external reference and no region,
     * and a person's region has origin HUMAN or REVIEWER, so neither counts. The review decision does not
     * change the answer: a detection a reviewer accepted is still a model's output.
     */
    public static Predicate isDetection(Root<Observation> observation, CommonAbstractCriteria query, CriteriaBuilder cb) {
        Subquery<UUID> producerRegion = query.subquery(UUID.class);
        Root<ObservationRegion> region = producerRegion.from(ObservationRegion.class);
        producerRegion.select(region.get("id")).where(
                cb.equal(region.get("observationId"), observation.get("id")),
                cb.equal(region.get("origin"), RegionOrigin.PRODUCER));
        return cb.or(
                cb.and(cb.equal(observation.get("source"), ObservationSource.AI),
                        cb.isNotNull(observation.get("externalRef"))),
                cb.exists(producerRegion));
    }

    /** The rows a caller without the detection role may see. */
    public static Specification<Observation> notADetection() {
        return (root, query, cb) -> cb.not(isDetection(root, query, cb));
    }

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
