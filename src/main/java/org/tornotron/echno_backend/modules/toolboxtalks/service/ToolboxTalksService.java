package org.tornotron.echno_backend.modules.toolboxtalks.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.modules.toolboxtalks.domain.ToolboxTalk;
import org.tornotron.echno_backend.modules.toolboxtalks.domain.ToolboxTalkStatus;
import org.tornotron.echno_backend.modules.toolboxtalks.dto.CreateToolboxTalkRequest;
import org.tornotron.echno_backend.modules.toolboxtalks.dto.ToolboxTalkDto;
import org.tornotron.echno_backend.modules.toolboxtalks.mapper.ToolboxTalksMapper;
import org.tornotron.echno_backend.modules.toolboxtalks.repository.ToolboxTalkRepository;

/**
 * Toolbox talks as the API sees them. Every read goes through a scoped query so a foreign id
 * reads as absent; every write stamps the current tenant and user.
 */
@Service
@RequiredArgsConstructor
public class ToolboxTalksService {

    private final ToolboxTalkRepository talks;
    private final TenantEntityHelper tenantEntityHelper;
    private final UserContextService userContextService;
    private final ToolboxTalksMapper mapper;

    @Transactional
    public ToolboxTalkDto create(CreateToolboxTalkRequest req) {
        ToolboxTalk talk = new ToolboxTalk();
        talk.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        talk.setProjectId(req.projectId());
        talk.setSpatialNodeId(req.spatialNodeId());
        talk.setTopic(req.topic().trim());
        talk.setTalkDate(req.talkDate());
        talk.setTalkTime(req.talkTime());
        talk.setConductorEmployeeId(req.conductorEmployeeId());
        talk.setNotes(req.notes());
        for (Long employeeId : req.attendeeEmployeeIds() == null ? List.<Long>of() : req.attendeeEmployeeIds()) {
            talk.addAttendee(employeeId);
        }
        talk.setCreatedBy(userContextService.getCurrentUserId());
        talk.setUpdatedBy(talk.getCreatedBy());
        return mapper.toDto(talks.save(talk));
    }

    @Transactional(readOnly = true)
    public Page<ToolboxTalkDto> list(Long projectId, LocalDate from, LocalDate to, ToolboxTalkStatus status,
                                     int page, int size) {
        return talks.findPage(projectId, from, to, status, PageRequest.of(page, size)).map(mapper::toDto);
    }

    @Transactional(readOnly = true)
    public ToolboxTalkDto get(UUID id) {
        return mapper.toDto(require(id));
    }

    private ToolboxTalk require(UUID id) {
        return talks.findByIdScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException("Toolbox talk not found: " + id));
    }
}
