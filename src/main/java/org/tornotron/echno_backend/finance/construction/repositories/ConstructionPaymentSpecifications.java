package org.tornotron.echno_backend.finance.construction.repositories;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.tornotron.echno_backend.finance.construction.ConstructionPayeeType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentVoucherStatus;
import org.tornotron.echno_backend.finance.construction.domain.ConstructionPayment;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the optional list filters as a JPA {@link Specification}. Only non-null
 * arguments become predicates, which avoids binding null-typed parameters on the
 * Postgres wire protocol. Organization scoping is handled separately by the
 * Hibernate {@code orgFilter} enabled per request.
 */
public final class ConstructionPaymentSpecifications {

    private ConstructionPaymentSpecifications() {}

    public static Specification<ConstructionPayment> withFilters(Long projectId,
                                                                 Long vendorId,
                                                                 ConstructionPaymentVoucherStatus status,
                                                                 ConstructionPaymentType type,
                                                                 ConstructionPayeeType payeeType) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (projectId != null) {
                predicates.add(cb.equal(root.get("projectId"), projectId));
            }
            if (vendorId != null) {
                predicates.add(cb.equal(root.get("vendorId"), vendorId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }
            if (payeeType != null) {
                predicates.add(cb.equal(root.get("payeeType"), payeeType));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
