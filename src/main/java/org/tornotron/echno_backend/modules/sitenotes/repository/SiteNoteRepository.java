package org.tornotron.echno_backend.modules.sitenotes.repository;

import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.ArrayList;
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
import org.tornotron.echno_backend.modules.sitenotes.domain.SiteNote;

@Repository
public interface SiteNoteRepository extends JpaRepository<SiteNote, UUID>, JpaSpecificationExecutor<SiteNote> {

    // JPQL rather than findById so the orgFilter applies; a stranger's id reads as absent.
    @Query("SELECT n FROM SiteNote n WHERE n.id = :id")
    Optional<SiteNote> findByIdScoped(@Param("id") UUID id);

    // Paged, never findAll(). Every filter is optional so one query serves the project page and
    // the date range. A Specification rather than "(:x IS NULL OR ...)" in JPQL, which
    // CockroachDB cannot type.
    default Page<SiteNote> findPage(Long projectId, LocalDate from, LocalDate to, Pageable pageable) {
        Specification<SiteNote> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (projectId != null) {
                where.add(cb.equal(root.get("projectId"), projectId));
            }
            if (from != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("noteDate"), from));
            }
            if (to != null) {
                where.add(cb.lessThanOrEqualTo(root.get("noteDate"), to));
            }
            return cb.and(where.toArray(new Predicate[0]));
        };
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("noteDate"), Sort.Order.desc("createdAt")));
        return findAll(spec, sorted);
    }

    // The projects that have a note on a day, for the reminder to subtract from the
    // organization's open projects. Runs inside a tenant, so the filter bounds it.
    @Query("SELECT DISTINCT n.projectId FROM SiteNote n WHERE n.noteDate = :day")
    List<Long> findProjectIdsWithNoteOn(@Param("day") LocalDate day);

    // The reminder's cross-tenant scan: every organization's id and active flag, scalars only,
    // because the caller has no tenant yet. Bounded by the page it is given.
    @Query("SELECT o.id AS id, o.isActive AS isActive FROM Organization o ORDER BY o.id")
    List<OrganizationRow> findOrganizationsForReminder(Pageable pageable);

    // The organization's projects a note is expected on. Runs inside a tenant.
    @Query("SELECT p.id AS id, p.projectName AS projectName FROM Project p "
            + "WHERE p.status IN (org.tornotron.echno_backend.project.enums.ProjectCreationStatus.open, "
            + "org.tornotron.echno_backend.project.enums.ProjectCreationStatus.approved) "
            + "ORDER BY p.id")
    List<OpenProjectRow> findOpenProjects(Pageable pageable);

    interface OrganizationRow {
        Long getId();

        Boolean getIsActive();
    }

    interface OpenProjectRow {
        Long getId();

        String getProjectName();
    }
}
