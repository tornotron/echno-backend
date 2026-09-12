package org.tornotron.echno_backend.modules.inspections.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.exception.UnprocessableRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.inspections.InspectionTrade;
import org.tornotron.echno_backend.modules.inspections.domain.OrgTrade;
import org.tornotron.echno_backend.modules.inspections.domain.TradeCatalogueEntry;
import org.tornotron.echno_backend.modules.inspections.dtos.CreateTradeRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.OrgTradeDto;
import org.tornotron.echno_backend.modules.inspections.dtos.TradeCatalogueDto;
import org.tornotron.echno_backend.modules.inspections.dtos.UpdateTradeRequest;
import org.tornotron.echno_backend.modules.inspections.mapper.TradeMapper;
import org.tornotron.echno_backend.modules.inspections.repositories.OrgTradeRepository;
import org.tornotron.echno_backend.modules.inspections.repositories.TradeCatalogueRepository;
import org.tornotron.echno_backend.organization.Organization;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The organization's trade list and the one place a trade reference is resolved. Every read
 * for a tenant first tops the org list up from the catalogue, so a fresh organization, or one
 * that predates a newly seeded catalogue code, sees the full list without a release step.
 * Idempotent on ({@code organization_id}, {@code catalogue_code}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeService {

    /** Org-defined trades sort after the seeded ones unless told otherwise. */
    static final int CUSTOM_SORT_ORDER = 1000;

    private final OrgTradeRepository orgTradeRepo;
    private final TradeCatalogueRepository catalogueRepo;
    private final TradeMapper mapper;
    private final TenantEntityHelper tenantEntityHelper;

    @Transactional
    public List<OrgTradeDto> listOrgTrades(boolean includeInactive) {
        Long orgId = ensureOrgTrades();
        List<OrgTrade> rows = includeInactive
                ? orgTradeRepo.findByOrganizationIdOrderBySortOrderAscNameAsc(orgId)
                : orgTradeRepo.findByOrganizationIdAndActiveTrueOrderBySortOrderAscNameAsc(orgId);
        return rows.stream().map(mapper::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<TradeCatalogueDto> listCatalogue() {
        return catalogueRepo.findAllByOrderBySortOrderAscCodeAsc().stream()
                .map(mapper::toCatalogueDto)
                .toList();
    }

    @Transactional
    public OrgTradeDto create(CreateTradeRequest req) {
        Long orgId = ensureOrgTrades();
        if (orgTradeRepo.existsByOrganizationIdAndCode(orgId, req.code())) {
            throw new DuplicateResourceException(
                    "This organization already has a trade with code " + req.code());
        }
        OrgTrade trade = new OrgTrade();
        trade.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        trade.setCode(req.code());
        trade.setName(req.name());
        trade.setGroupCode(req.groupCode());
        trade.setDescription(req.description());
        trade.setSortOrder(req.sortOrder() != null ? req.sortOrder() : CUSTOM_SORT_ORDER);
        trade.setActive(true);
        OrgTrade saved = orgTradeRepo.saveAndFlush(trade);
        log.info("Created trade {} ({}) for organization {}", saved.getCode(), saved.getId(), orgId);
        return mapper.toDto(saved);
    }

    @Transactional
    public OrgTradeDto update(UUID id, UpdateTradeRequest req) {
        OrgTrade trade = orgTradeRepo.findByIdScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException("Trade with ID " + id + " was not found"));
        if (req.name() != null) {
            if (req.name().isBlank()) {
                throw new InvalidRequestException("A trade name cannot be blank");
            }
            trade.setName(req.name());
        }
        if (req.groupCode() != null) {
            if (req.groupCode().isBlank()) {
                throw new InvalidRequestException("A trade group cannot be blank");
            }
            trade.setGroupCode(req.groupCode());
        }
        if (req.description() != null) {
            trade.setDescription(req.description());
        }
        if (req.sortOrder() != null) {
            trade.setSortOrder(req.sortOrder());
        }
        if (req.active() != null) {
            trade.setActive(req.active());
        }
        return mapper.toDto(orgTradeRepo.saveAndFlush(trade));
    }

    /**
     * Resolves a trade reference from a write payload. {@code tradeId} wins when both are
     * given and must name a row of the current organization; a slug resolves by code. Null
     * for both means no trade, which safety and compliance inspections rely on.
     *
     * @throws UnprocessableRequestException when the slug or id names no trade of the tenant
     */
    @Transactional
    public OrgTrade resolve(String slug, UUID tradeId) {
        if (tradeId == null && (slug == null || slug.isBlank())) {
            return null;
        }
        Long orgId = ensureOrgTrades();
        if (tradeId != null) {
            return orgTradeRepo.findByIdScoped(tradeId)
                    .filter(t -> t.getOrganization().getId().equals(orgId))
                    .orElseThrow(() -> new UnprocessableRequestException(
                            "Trade " + tradeId + " is not a trade of this organization"));
        }
        String code = slug.trim().toLowerCase();
        return orgTradeRepo.findByOrganizationIdAndCode(orgId, code)
                .orElseThrow(() -> new UnprocessableRequestException(
                        "Unknown inspection trade: " + slug
                                + ". Define it under the organization's trades first."));
    }

    /** Tops up the current organization's trade list from the catalogue. Returns the org id. */
    @Transactional
    public Long ensureOrgTrades() {
        Long orgId = TenantContext.getCurrentOrgId();
        Organization org = tenantEntityHelper.resolveCurrentOrganization();
        ensureOrgTrades(org, orgId);
        return orgId;
    }

    @Transactional
    public int ensureOrgTrades(Organization organization) {
        return ensureOrgTrades(organization, organization.getId());
    }

    private int ensureOrgTrades(Organization organization, Long orgId) {
        Set<String> present = new HashSet<>(orgTradeRepo.findCatalogueCodes(orgId));
        int added = 0;
        for (TradeCatalogueEntry entry : catalogueRepo.findByActiveTrueOrderBySortOrderAscCodeAsc()) {
            if (present.contains(entry.getCode())
                    || orgTradeRepo.existsByOrganizationIdAndCode(orgId, entry.getCode())) {
                continue;
            }
            OrgTrade copy = new OrgTrade();
            copy.setOrganization(organization);
            copy.setCode(entry.getCode());
            copy.setName(entry.getName());
            copy.setGroupCode(entry.getGroupCode());
            copy.setDescription(entry.getDescription());
            copy.setSortOrder(entry.getSortOrder());
            copy.setActive(true);
            copy.setCatalogueCode(entry.getCode());
            copy.setLegacyEnum(InspectionTrade.find(entry.getCode()).map(Enum::name).orElse(null));
            orgTradeRepo.save(copy);
            added++;
        }
        if (added > 0) {
            orgTradeRepo.flush();
            log.info("Copied {} catalogue trades into organization {}", added, orgId);
        }
        return added;
    }
}
