package org.tornotron.echno_backend.modules.toolboxtalks.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.toolboxtalks.domain.ToolboxTalksEntry;
import org.tornotron.echno_backend.modules.toolboxtalks.dto.CreateToolboxTalksEntryRequest;
import org.tornotron.echno_backend.modules.toolboxtalks.dto.ToolboxTalksEntryDto;
import org.tornotron.echno_backend.modules.toolboxtalks.mapper.ToolboxTalksMapper;
import org.tornotron.echno_backend.modules.toolboxtalks.repository.ToolboxTalksEntryRepository;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Toolbox Talks entries as the API sees them. Every read goes through a scoped query so a
 * foreign id reads as absent; every write stamps the current tenant and user.
 */
@Service
@RequiredArgsConstructor
public class ToolboxTalksService {

    private final ToolboxTalksEntryRepository entries;
    private final TenantEntityHelper tenantEntityHelper;
    private final UserContextService userContextService;
    private final ToolboxTalksMapper mapper;

    @Transactional
    public ToolboxTalksEntryDto create(CreateToolboxTalksEntryRequest req) {
        ToolboxTalksEntry entry = new ToolboxTalksEntry();
        entry.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        entry.setTitle(req.title().trim());
        entry.setNotes(req.notes());
        entry.setCreatedBy(userContextService.getCurrentUserId());
        entry.setUpdatedBy(entry.getCreatedBy());
        return mapper.toDto(entries.save(entry));
    }

    @Transactional(readOnly = true)
    public Page<ToolboxTalksEntryDto> list(int page, int size) {
        return entries.findPage(PageRequest.of(page, size)).map(mapper::toDto);
    }

    @Transactional(readOnly = true)
    public ToolboxTalksEntryDto get(UUID id) {
        return mapper.toDto(require(id));
    }

    private ToolboxTalksEntry require(UUID id) {
        return entries.findByIdScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException("Toolbox Talks entry not found: " + id));
    }
}
