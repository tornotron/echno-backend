package org.tornotron.echno_backend.inspection.repositories;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.tornotron.echno_backend.inspection.InspectionCategory;
import org.tornotron.echno_backend.inspection.InspectionResult;
import org.tornotron.echno_backend.inspection.InspectionStatus;
import org.tornotron.echno_backend.inspection.InspectionTrade;
import org.tornotron.echno_backend.inspection.InspectionType;
import org.tornotron.echno_backend.inspection.domain.Inspection;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the optional list filters as a JPA {@link Specification}. Only non-null
 * arguments become predicates, which avoids binding null-typed parameters on the
 * Postgres wire protocol. Organization scoping is handled separately by the
 * Hibernate {@code orgFilter} enabled per request.
 */
public final class InspectionSpecifications {

    private InspectionSpecifications() {}

    public static Specification<Inspection> withFilters(Long projectId,
                                                        InspectionStatus status,
                                                        InspectionType type,
                                                        InspectionCategory category,
                                                        InspectionTrade trade,
                                                        InspectionResult result) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
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
                predicates.add(cb.equal(root.get("trade"), trade));
            }
            if (result != null) {
                predicates.add(cb.equal(root.get("result"), result));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
