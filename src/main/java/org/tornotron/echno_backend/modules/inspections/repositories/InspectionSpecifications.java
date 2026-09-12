package org.tornotron.echno_backend.modules.inspections.repositories;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import org.tornotron.echno_backend.modules.inspections.InspectionCategory;
import org.tornotron.echno_backend.modules.inspections.InspectionResult;
import org.tornotron.echno_backend.modules.inspections.InspectionStatus;
import java.util.UUID;
import org.tornotron.echno_backend.modules.inspections.InspectionType;
import org.tornotron.echno_backend.modules.inspections.domain.Inspection;
import org.tornotron.echno_backend.project.spatial.SpatialNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds the optional list filters as a JPA {@link Specification}. Only non-null
 * arguments become predicates, which avoids binding null-typed parameters on the
 * Postgres wire protocol. Organization scoping is handled separately by the
 * Hibernate {@code orgFilter} enabled per request.
 */
public final class InspectionSpecifications {

    /** Sentinel prefix for a node the caller cannot see: matches nothing. */
    public static final String NO_MATCH = "\u0000";

    private InspectionSpecifications() {}

    public static Specification<Inspection> withFilters(Long projectId,
                                                        InspectionStatus status,
                                                        InspectionType type,
                                                        InspectionCategory category,
                                                        String trade,
                                                        UUID tradeId,
                                                        InspectionResult result) {
        return withFilters(projectId, status, type, category, trade, tradeId, result, null);
    }

    /**
     * As above, plus the subtree filter: with a path prefix, only inspections whose node's
     * path starts with it, which is the node itself and everything under it. The prefix comes
     * from an org-scoped lookup of the requested node; a node the caller cannot see yields
     * no prefix and the caller passes {@link #NO_MATCH} to get an empty page.
     */
    public static Specification<Inspection> withFilters(Long projectId,
                                                        InspectionStatus status,
                                                        InspectionType type,
                                                        InspectionCategory category,
                                                        String trade,
                                                        UUID tradeId,
                                                        InspectionResult result,
                                                        String spatialPathPrefix) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (NO_MATCH.equals(spatialPathPrefix)) {
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
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }
            if (category != null) {
                predicates.add(cb.equal(root.get("category"), category));
            }
            if (trade != null) {
                predicates.add(cb.equal(root.get("tradeRef").get("code"), trade.trim().toLowerCase()));
            }
            if (tradeId != null) {
                predicates.add(cb.equal(root.get("tradeRef").get("id"), tradeId));
            }
            if (result != null) {
                predicates.add(cb.equal(root.get("result"), result));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
