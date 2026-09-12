package org.tornotron.echno_backend.modules.inspections.repositories;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionEvent;

import java.util.ArrayList;
import java.util.List;

class InspectionEventQueriesImpl implements InspectionEventQueries {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<InspectionEvent> search(Long organizationId, InspectionEventFilter filter, Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<InspectionEvent> query = cb.createQuery(InspectionEvent.class);
        Root<InspectionEvent> root = query.from(InspectionEvent.class);
        query.where(predicates(cb, root, organizationId, filter))
                .orderBy(cb.asc(root.get("occurredAt")), cb.asc(root.get("id")));
        TypedQuery<InspectionEvent> typed = entityManager.createQuery(query);
        if (pageable.isPaged()) {
            typed.setFirstResult((int) pageable.getOffset());
            typed.setMaxResults(pageable.getPageSize());
        }
        List<InspectionEvent> content = typed.getResultList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<InspectionEvent> countRoot = countQuery.from(InspectionEvent.class);
        countQuery.select(cb.count(countRoot))
                .where(predicates(cb, countRoot, organizationId, filter));
        long total = entityManager.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }

    private static Predicate[] predicates(CriteriaBuilder cb, Root<InspectionEvent> root,
                                          Long organizationId, InspectionEventFilter f) {
        List<Predicate> where = new ArrayList<>();
        where.add(cb.equal(root.get("organization").get("id"), organizationId));
        if (f.inspectionId() != null) {
            where.add(cb.equal(root.get("inspectionId"), f.inspectionId()));
        }
        if (f.subjectType() != null) {
            where.add(cb.equal(root.get("subjectType"), f.subjectType()));
        }
        if (f.subjectIds() != null && !f.subjectIds().isEmpty()) {
            where.add(root.get("subjectId").in(f.subjectIds()));
        }
        if (f.projectId() != null) {
            where.add(cb.equal(root.get("projectId"), f.projectId()));
        }
        if (f.eventType() != null && !f.eventType().isBlank()) {
            where.add(cb.equal(root.get("eventType"), f.eventType()));
        }
        if (f.actorId() != null && !f.actorId().isBlank()) {
            where.add(cb.equal(root.get("actorId"), f.actorId()));
        }
        if (f.from() != null) {
            where.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), f.from()));
        }
        if (f.to() != null) {
            where.add(cb.lessThanOrEqualTo(root.get("occurredAt"), f.to()));
        }
        return where.toArray(Predicate[]::new);
    }
}
