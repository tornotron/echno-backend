package org.tornotron.echno_backend.modules.__MODULE_PKG__.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.__MODULE_PKG__.domain.__MODULE_PASCAL__Entry;
import org.tornotron.echno_backend.modules.__MODULE_PKG__.dto.Create__MODULE_PASCAL__EntryRequest;
import org.tornotron.echno_backend.modules.__MODULE_PKG__.dto.__MODULE_PASCAL__EntryDto;
import org.tornotron.echno_backend.modules.__MODULE_PKG__.mapper.__MODULE_PASCAL__Mapper;
import org.tornotron.echno_backend.modules.__MODULE_PKG__.repository.__MODULE_PASCAL__EntryRepository;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * __MODULE_NAME__ entries as the API sees them. Every read goes through a scoped query so a
 * foreign id reads as absent; every write stamps the current tenant and user.
 */
@Service
@RequiredArgsConstructor
public class __MODULE_PASCAL__Service {

    private final __MODULE_PASCAL__EntryRepository entries;
    private final TenantEntityHelper tenantEntityHelper;
    private final UserContextService userContextService;
    private final __MODULE_PASCAL__Mapper mapper;

    @Transactional
    public __MODULE_PASCAL__EntryDto create(Create__MODULE_PASCAL__EntryRequest req) {
        __MODULE_PASCAL__Entry entry = new __MODULE_PASCAL__Entry();
        entry.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        entry.setTitle(req.title().trim());
        entry.setNotes(req.notes());
        entry.setCreatedBy(userContextService.getCurrentUserId());
        entry.setUpdatedBy(entry.getCreatedBy());
        return mapper.toDto(entries.save(entry));
    }

    @Transactional(readOnly = true)
    public Page<__MODULE_PASCAL__EntryDto> list(int page, int size) {
        return entries.findPage(PageRequest.of(page, size)).map(mapper::toDto);
    }

    @Transactional(readOnly = true)
    public __MODULE_PASCAL__EntryDto get(UUID id) {
        return mapper.toDto(require(id));
    }

    private __MODULE_PASCAL__Entry require(UUID id) {
        return entries.findByIdScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException("__MODULE_NAME__ entry not found: " + id));
    }
}
