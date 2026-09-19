package org.tornotron.echno_backend.modules.toolboxtalks.repository;

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
import org.tornotron.echno_backend.modules.toolboxtalks.domain.ToolboxTalk;
import org.tornotron.echno_backend.modules.toolboxtalks.domain.ToolboxTalkStatus;

@Repository
public interface ToolboxTalkRepository extends JpaRepository<ToolboxTalk, UUID>, JpaSpecificationExecutor<ToolboxTalk> {

    // JPQL rather than findById so the orgFilter applies; a stranger's id reads as absent.
    @Query("SELECT t FROM ToolboxTalk t WHERE t.id = :id")
    Optional<ToolboxTalk> findByIdScoped(@Param("id") UUID id);

    // Paged, never findAll(): a site records a talk every working day. Every filter is
    // optional so one query serves the project page, the date range and the status tab. A
    // Specification rather than "(:x IS NULL OR ...)" in JPQL, which CockroachDB cannot type.
    default Page<ToolboxTalk> findPage(Long projectId, LocalDate from, LocalDate to,
                                       ToolboxTalkStatus status, Pageable pageable) {
        Specification<ToolboxTalk> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (projectId != null) {
                where.add(cb.equal(root.get("projectId"), projectId));
            }
            if (from != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("talkDate"), from));
            }
            if (to != null) {
                where.add(cb.lessThanOrEqualTo(root.get("talkDate"), to));
            }
            if (status != null) {
                where.add(cb.equal(root.get("status"), status));
            }
            return cb.and(where.toArray(new Predicate[0]));
        };
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("talkDate"), Sort.Order.desc("createdAt")));
        return findAll(spec, sorted);
    }

    // The projects that have a recorded talk on a day, for the reminder to subtract from the
    // organization's open projects. Runs inside a tenant, so the filter bounds it.
    @Query("SELECT DISTINCT t.projectId FROM ToolboxTalk t "
            + "WHERE t.talkDate = :day AND t.status = org.tornotron.echno_backend.modules.toolboxtalks.domain.ToolboxTalkStatus.RECORDED")
    List<Long> findProjectIdsWithRecordedTalkOn(@Param("day") LocalDate day);

    // The reminder's cross-tenant scan: every organization's id and active flag, scalars only,
    // because the caller has no tenant yet. Bounded by the page it is given.
    @Query("SELECT o.id AS id, o.isActive AS isActive FROM Organization o ORDER BY o.id")
    List<OrganizationRow> findOrganizationsForReminder(Pageable pageable);

    // The organization's projects a talk is expected on. Runs inside a tenant.
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
