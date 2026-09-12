package org.tornotron.echno_backend.modules.inspections.repositories;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import java.util.UUID;
import org.tornotron.echno_backend.modules.inspections.domain.ChecklistTemplate;

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

    public static Specification<ChecklistTemplate> withFilters(String trade, UUID tradeId, Boolean active) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (trade != null) {
                predicates.add(cb.equal(root.get("tradeRef").get("code"), trade.trim().toLowerCase()));
            }
            if (tradeId != null) {
                predicates.add(cb.equal(root.get("tradeRef").get("id"), tradeId));
            }
            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
