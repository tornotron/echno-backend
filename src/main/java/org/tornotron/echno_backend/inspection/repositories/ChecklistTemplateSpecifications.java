package org.tornotron.echno_backend.inspection.repositories;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.tornotron.echno_backend.inspection.InspectionTrade;
import org.tornotron.echno_backend.inspection.domain.ChecklistTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the optional list filters as a JPA {@link Specification}. Only non-null
 * arguments become predicates, which avoids binding null-typed parameters on the
 * Postgres wire protocol. Organization scoping is handled separately by the
 * Hibernate {@code orgFilter} enabled per request.
 */
public final class ChecklistTemplateSpecifications {

    private ChecklistTemplateSpecifications() {}

    public static Specification<ChecklistTemplate> withFilters(InspectionTrade trade, Boolean active) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (trade != null) {
                predicates.add(cb.equal(root.get("trade"), trade));
            }
            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
