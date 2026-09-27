package org.tornotron.echno_backend.modules.workprogress.repository;

import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.workprogress.domain.ProgressInspection;

@Repository
public interface ProgressInspectionRepository
        extends JpaRepository<ProgressInspection, UUID>, JpaSpecificationExecutor<ProgressInspection> {

    // JPQL rather than findById so the orgFilter applies; a stranger's id reads as absent. The
    // organization is also named, so the read holds where the filter is not enabled.
    @Query("SELECT p FROM ProgressInspection p WHERE p.id = :id AND p.organization.id = :orgId")
    Optional<ProgressInspection> findScoped(@Param("id") UUID id, @Param("orgId") Long orgId);

    // Paged, newest first. A Specification rather than "(:x IS NULL OR ...)" in JPQL, which
    // CockroachDB cannot type.
    default Page<ProgressInspection> findPage(Long orgId, Long projectId, Long wbsElementId, Pageable pageable) {
        Specification<ProgressInspection> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("organization").get("id"), orgId));
            if (projectId != null) {
                where.add(cb.equal(root.get("projectId"), projectId));
            }
            if (wbsElementId != null) {
                where.add(cb.equal(root.get("wbsElementId"), wbsElementId));
            }
            return cb.and(where.toArray(new Predicate[0]));
        };
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("inspectionDate"), Sort.Order.desc("createdAt")));
        return findAll(spec, sorted);
    }

    @Query("SELECT MAX(p.inspectionDate) FROM ProgressInspection p WHERE p.wbsElementId = :elementId AND p.organization.id = :orgId")
    Optional<LocalDate> findLatestInspectionDate(@Param("elementId") Long elementId, @Param("orgId") Long orgId);

    @Query("SELECT COUNT(p) > 0 FROM ProgressInspection p WHERE p.wbsElementId IN :ids AND p.organization.id = :orgId")
    boolean existsForElements(@Param("ids") Collection<Long> ids, @Param("orgId") Long orgId);
}
