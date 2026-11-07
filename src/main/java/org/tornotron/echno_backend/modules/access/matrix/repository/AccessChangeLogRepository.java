package org.tornotron.echno_backend.modules.access.matrix.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessChangeLog;

/**
 * The change history: insert and read only. It extends the bare {@link Repository} on purpose, so
 * there is no inherited delete or batch write, and {@code AccessChangeLogAppendOnlyTest} keeps it
 * that way.
 */
public interface AccessChangeLogRepository extends Repository<AccessChangeLog, UUID> {

    AccessChangeLog save(AccessChangeLog row);

    @Query("SELECT c FROM AccessChangeLog c WHERE c.targetType = :targetType AND c.targetId = :targetId "
            + "ORDER BY c.changedAt")
    List<AccessChangeLog> findByTarget(@Param("targetType") String targetType, @Param("targetId") String targetId);
}
