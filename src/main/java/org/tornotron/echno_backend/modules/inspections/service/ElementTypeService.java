package org.tornotron.echno_backend.modules.inspections.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.inspections.domain.ElementTypeCatalogueEntry;
import org.tornotron.echno_backend.modules.inspections.domain.OrgElementType;
import org.tornotron.echno_backend.modules.inspections.dtos.CreateElementTypeRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.ElementTypeCatalogueDto;
import org.tornotron.echno_backend.modules.inspections.dtos.OrgElementTypeDto;
import org.tornotron.echno_backend.modules.inspections.dtos.UpdateElementTypeRequest;
import org.tornotron.echno_backend.modules.inspections.mapper.ElementTypeMapper;
import org.tornotron.echno_backend.modules.inspections.repositories.ElementTypeCatalogueRepository;
import org.tornotron.echno_backend.modules.inspections.repositories.OrgElementTypeRepository;
import org.tornotron.echno_backend.organization.Organization;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The organization's element type list, topped up from the catalogue on every read the same
 * way {@link TradeService} does for trades. {@link #isKnownCode} is the validation hook a
 * spatial node's {@code elementType} slug is checked against.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ElementTypeService {

    static final int CUSTOM_SORT_ORDER = 1000;

    private final OrgElementTypeRepository orgRepo;
    private final ElementTypeCatalogueRepository catalogueRepo;
    private final ElementTypeMapper mapper;
    private final TenantEntityHelper tenantEntityHelper;

    @Transactional
    public List<OrgElementTypeDto> listOrgElementTypes(boolean includeInactive) {
        Long orgId = ensureOrgElementTypes();
        List<OrgElementType> rows = includeInactive
                ? orgRepo.findByOrganizationIdOrderBySortOrderAscNameAsc(orgId)
                : orgRepo.findByOrganizationIdAndActiveTrueOrderBySortOrderAscNameAsc(orgId);
        return rows.stream().map(mapper::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<ElementTypeCatalogueDto> listCatalogue() {
        return catalogueRepo.findAllByOrderBySortOrderAscCodeAsc().stream()
                .map(mapper::toCatalogueDto)
                .toList();
    }

    @Transactional
    public OrgElementTypeDto create(CreateElementTypeRequest req) {
        Long orgId = ensureOrgElementTypes();
        if (orgRepo.existsByOrganizationIdAndCode(orgId, req.code())) {
            throw new DuplicateResourceException(
                    "This organization already has an element type with code " + req.code());
        }
        OrgElementType row = new OrgElementType();
        row.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        row.setCode(req.code());
        row.setName(req.name());
        row.setGroupCode(req.groupCode());
        row.setDescription(req.description());
        row.setSortOrder(req.sortOrder() != null ? req.sortOrder() : CUSTOM_SORT_ORDER);
        row.setActive(true);
        OrgElementType saved = orgRepo.saveAndFlush(row);
        log.info("Created element type {} ({}) for organization {}", saved.getCode(), saved.getId(), orgId);
        return mapper.toDto(saved);
    }

    @Transactional
    public OrgElementTypeDto update(UUID id, UpdateElementTypeRequest req) {
        OrgElementType row = orgRepo.findByIdScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException("Element type with ID " + id + " was not found"));
        if (req.name() != null) {
            if (req.name().isBlank()) {
                throw new InvalidRequestException("An element type name cannot be blank");
            }
            row.setName(req.name());
        }
        if (req.groupCode() != null) {
            if (req.groupCode().isBlank()) {
                throw new InvalidRequestException("An element type group cannot be blank");
            }
            row.setGroupCode(req.groupCode());
        }
        if (req.description() != null) {
            row.setDescription(req.description());
        }
        if (req.sortOrder() != null) {
            row.setSortOrder(req.sortOrder());
        }
        if (req.active() != null) {
            row.setActive(req.active());
        }
        return mapper.toDto(orgRepo.saveAndFlush(row));
    }

    /**
     * Whether the current organization has an element type with this code, active or not.
     * Retired types stay valid on the elements and templates that already name them.
     */
    @Transactional
    public boolean isKnownCode(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        Long orgId = ensureOrgElementTypes();
        return orgRepo.existsByOrganizationIdAndCode(orgId, code.trim().toLowerCase());
    }

    /** Whether the current organization has an active element type with this code. */
    @Transactional
    public boolean isActiveCode(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        Long orgId = ensureOrgElementTypes();
        return orgRepo.existsByOrganizationIdAndCodeAndActiveTrue(orgId, code.trim().toLowerCase());
    }

    @Transactional
    public Long ensureOrgElementTypes() {
        Long orgId = TenantContext.getCurrentOrgId();
        Organization org = tenantEntityHelper.resolveCurrentOrganization();
        ensureOrgElementTypes(org, orgId);
        return orgId;
    }

    @Transactional
    public int ensureOrgElementTypes(Organization organization) {
        return ensureOrgElementTypes(organization, organization.getId());
    }

    private int ensureOrgElementTypes(Organization organization, Long orgId) {
        Set<String> present = new HashSet<>(orgRepo.findCatalogueCodes(orgId));
        int added = 0;
        for (ElementTypeCatalogueEntry entry : catalogueRepo.findByActiveTrueOrderBySortOrderAscCodeAsc()) {
            if (present.contains(entry.getCode())
                    || orgRepo.existsByOrganizationIdAndCode(orgId, entry.getCode())) {
                continue;
            }
            OrgElementType copy = new OrgElementType();
            copy.setOrganization(organization);
            copy.setCode(entry.getCode());
            copy.setName(entry.getName());
            copy.setGroupCode(entry.getGroupCode());
            copy.setDescription(entry.getDescription());
            copy.setSortOrder(entry.getSortOrder());
            copy.setActive(true);
            copy.setCatalogueCode(entry.getCode());
            orgRepo.save(copy);
            added++;
        }
        if (added > 0) {
            orgRepo.flush();
            log.info("Copied {} catalogue element types into organization {}", added, orgId);
        }
        return added;
    }
}
