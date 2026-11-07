package org.tornotron.echno_backend.modules.access.matrix.service;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.modules.access.api.AccessChanges;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessChangeLog;
import org.tornotron.echno_backend.modules.access.matrix.repository.AccessChangeLogRepository;
import org.tornotron.echno_backend.organization.OrganizationRepository;

import java.util.Map;

/** {@link AccessChanges} on {@code access_change_log}. */
@Service
public class AccessChangeRecorder implements AccessChanges {

    private final AccessChangeLogRepository changeLog;
    private final OrganizationRepository organizations;

    public AccessChangeRecorder(AccessChangeLogRepository changeLog, OrganizationRepository organizations) {
        this.changeLog = changeLog;
        this.organizations = organizations;
    }

    @Override
    @Transactional
    public void record(Long organizationId, @Nullable Long actorUserId, String action, String targetType,
                       String targetId, @Nullable Map<String, Object> before, @Nullable Map<String, Object> after) {
        changeLog.save(AccessChangeLog.of(organizations.getReferenceById(organizationId), actorUserId, action,
                targetType, targetId, before, after));
    }
}
