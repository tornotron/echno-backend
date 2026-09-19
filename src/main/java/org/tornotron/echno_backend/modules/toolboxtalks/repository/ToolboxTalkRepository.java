package org.tornotron.echno_backend.modules.toolboxtalks.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.toolboxtalks.domain.ToolboxTalk;
import org.tornotron.echno_backend.modules.toolboxtalks.domain.ToolboxTalkStatus;

@Repository
public interface ToolboxTalkRepository extends JpaRepository<ToolboxTalk, UUID> {

    // JPQL rather than findById so the orgFilter applies; a stranger's id reads as absent.
    @Query("SELECT t FROM ToolboxTalk t WHERE t.id = :id")
    Optional<ToolboxTalk> findByIdScoped(@Param("id") UUID id);

    // Paged, never findAll(): a site records a talk every working day. Every filter is
    // optional so one query serves the project page, the date range and the status tab.
    @Query("SELECT t FROM ToolboxTalk t "
            + "WHERE (:projectId IS NULL OR t.projectId = :projectId) "
            + "AND (:from IS NULL OR t.talkDate >= :from) "
            + "AND (:to IS NULL OR t.talkDate <= :to) "
            + "AND (:status IS NULL OR t.status = :status) "
            + "ORDER BY t.talkDate DESC, t.createdAt DESC")
    Page<ToolboxTalk> findPage(@Param("projectId") Long projectId,
                               @Param("from") LocalDate from,
                               @Param("to") LocalDate to,
                               @Param("status") ToolboxTalkStatus status,
                               Pageable pageable);

    // The projects that have a recorded talk on a day, for the reminder to subtract from the
    // organization's open projects. Runs inside a tenant, so the filter bounds it.
    @Query("SELECT DISTINCT t.projectId FROM ToolboxTalk t "
            + "WHERE t.talkDate = :day AND t.status = org.tornotron.echno_backend.modules.toolboxtalks.domain.ToolboxTalkStatus.RECORDED")
    List<Long> findProjectIdsWithRecordedTalkOn(@Param("day") LocalDate day);
}
