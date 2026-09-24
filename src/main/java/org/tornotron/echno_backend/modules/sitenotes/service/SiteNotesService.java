package org.tornotron.echno_backend.modules.sitenotes.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.sitenotes.domain.SiteNotesEntry;
import org.tornotron.echno_backend.modules.sitenotes.dto.CreateSiteNotesEntryRequest;
import org.tornotron.echno_backend.modules.sitenotes.dto.SiteNotesEntryDto;
import org.tornotron.echno_backend.modules.sitenotes.mapper.SiteNotesMapper;
import org.tornotron.echno_backend.modules.sitenotes.repository.SiteNotesEntryRepository;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Site Notes entries as the API sees them. Every read goes through a scoped query so a
 * foreign id reads as absent; every write stamps the current tenant and user.
 */
@Service
@RequiredArgsConstructor
public class SiteNotesService {

    private final SiteNotesEntryRepository entries;
    private final TenantEntityHelper tenantEntityHelper;
    private final UserContextService userContextService;
    private final SiteNotesMapper mapper;

    @Transactional
    public SiteNotesEntryDto create(CreateSiteNotesEntryRequest req) {
        SiteNotesEntry entry = new SiteNotesEntry();
        entry.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        entry.setTitle(req.title().trim());
        entry.setNotes(req.notes());
        entry.setCreatedBy(userContextService.getCurrentUserId());
        entry.setUpdatedBy(entry.getCreatedBy());
        return mapper.toDto(entries.save(entry));
    }

    @Transactional(readOnly = true)
    public Page<SiteNotesEntryDto> list(int page, int size) {
        return entries.findPage(PageRequest.of(page, size)).map(mapper::toDto);
    }

    @Transactional(readOnly = true)
    public SiteNotesEntryDto get(UUID id) {
        return mapper.toDto(require(id));
    }

    private SiteNotesEntry require(UUID id) {
        return entries.findByIdScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException("Site Notes entry not found: " + id));
    }
}
