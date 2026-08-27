package org.tornotron.echno_backend.inspection.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.inspection.CheckItemStatus;
import org.tornotron.echno_backend.inspection.InspectionTrade;
import org.tornotron.echno_backend.inspection.domain.ChecklistTemplate;
import org.tornotron.echno_backend.inspection.domain.ChecklistTemplateItem;
import org.tornotron.echno_backend.inspection.domain.InspectionCheckItem;
import org.tornotron.echno_backend.inspection.domain.StarterChecklistTemplate;
import org.tornotron.echno_backend.inspection.domain.StarterChecklistTemplateItem;
import org.tornotron.echno_backend.inspection.dtos.ChecklistTemplateDto;
import org.tornotron.echno_backend.inspection.dtos.ChecklistTemplateItemRequest;
import org.tornotron.echno_backend.inspection.dtos.ChecklistTemplateRequest;
import org.tornotron.echno_backend.inspection.dtos.StarterChecklistTemplateDto;
import org.tornotron.echno_backend.inspection.mapper.ChecklistTemplateMapper;
import org.tornotron.echno_backend.inspection.repositories.ChecklistTemplateRepository;
import org.tornotron.echno_backend.inspection.repositories.ChecklistTemplateSpecifications;
import org.tornotron.echno_backend.inspection.repositories.StarterChecklistTemplateRepository;

import java.util.List;
import java.util.UUID;

/**
 * The per-trade checklist library an organization inspects against.
 *
 * <p>Two populations sit behind this service and they must not be confused. The
 * {@code starter_checklist_templates} are global reference data shipped with the
 * product, read-only, and shared by every tenant. The {@code checklist_templates}
 * are an org's own, tenant-scoped and editable. {@link #adoptStarter} is the only
 * bridge: it copies a starter into the calling tenant, after which the two are
 * independent, so a later revision of the shipped starter never rewrites criteria
 * a client has tailored and signed work off against.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChecklistTemplateService {

    private final ChecklistTemplateRepository templateRepo;
    private final StarterChecklistTemplateRepository starterRepo;
    private final ChecklistTemplateMapper mapper;
    private final TenantEntityHelper tenantEntityHelper;

    @Transactional(readOnly = true)
    public ChecklistTemplateDto findById(UUID id) {
        return mapper.toDto(require(id));
    }

    @Transactional(readOnly = true)
    public Page<ChecklistTemplateDto> findAll(InspectionTrade trade, Boolean active, Pageable pageable) {
        return templateRepo.findAll(ChecklistTemplateSpecifications.withFilters(trade, active), pageable)
                .map(mapper::toDto);
    }

    /**
     * Defines the organization's checklist for a trade.
     *
     * @throws DuplicateResourceException if the tenant already has one for that trade.
     *                                    There is exactly one template per trade per
     *                                    org, so a second definition is an edit of the
     *                                    first, not a new row.
     */
    @Transactional
    public ChecklistTemplateDto create(ChecklistTemplateRequest req) {
        requireTradeIsFree(req.trade());

        ChecklistTemplate template = new ChecklistTemplate();
        template.setTrade(req.trade());
        template.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        apply(template, req);

        ChecklistTemplate saved = templateRepo.saveAndFlush(template);
        log.info("Created checklist template {} for trade {}", saved.getId(), saved.getTrade());
        return mapper.toDto(saved);
    }

    /**
     * Replaces a checklist template's name, description, active flag and check
     * points, and bumps its revision counter.
     *
     * <p>The trade is fixed when the template is created: it is the key the
     * template is found by when an inspection is instantiated, and moving one to
     * another trade would silently repoint every future inspection of two trades at
     * once. Retire the template and define one for the other trade instead.
     *
     * @throws ResourceNotFoundException if no such template exists in this tenant.
     * @throws InvalidRequestException   if the payload names a different trade.
     */
    @Transactional
    public ChecklistTemplateDto update(UUID id, ChecklistTemplateRequest req) {
        ChecklistTemplate template = require(id);
        if (req.trade() != null && req.trade() != template.getTrade()) {
            throw new InvalidRequestException(
                    "Checklist template " + id + " covers trade " + template.getTrade().getValue()
                            + " and cannot be moved to " + req.trade().getValue()
                            + ". The trade is fixed when the template is created.");
        }

        template.getItems().clear();
        apply(template, req);
        template.setVersion(template.getVersion() + 1);

        ChecklistTemplate saved = templateRepo.saveAndFlush(template);
        log.info("Updated checklist template {} to version {}", saved.getId(), saved.getVersion());
        return mapper.toDto(saved);
    }

    /**
     * The starter checklists on offer, one per trade at most. Global reference data,
     * identical for every tenant.
     */
    @Transactional(readOnly = true)
    public List<StarterChecklistTemplateDto> findStarters() {
        return starterRepo.findByActiveTrueOrderByTradeAsc().stream()
                .map(mapper::toStarterDto)
                .toList();
    }

    /**
     * Copies the shipped starter for a trade into the calling tenant as its own
     * editable template. The copy is a snapshot: nothing links the two afterwards.
     *
     * @throws ResourceNotFoundException  if no active starter exists for the trade.
     * @throws DuplicateResourceException if the tenant already has a template for it.
     */
    @Transactional
    public ChecklistTemplateDto adoptStarter(InspectionTrade trade) {
        requireTradeIsFree(trade);

        StarterChecklistTemplate starter = starterRepo.findByTradeAndActiveTrue(trade)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No starter checklist is available for trade " + trade.getValue()));

        ChecklistTemplate template = new ChecklistTemplate();
        template.setTrade(starter.getTrade());
        template.setName(starter.getName());
        template.setDescription(starter.getDescription());
        template.setActive(true);
        template.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        for (StarterChecklistTemplateItem source : starter.getItems()) {
            ChecklistTemplateItem item = new ChecklistTemplateItem();
            item.setCategory(source.getCategory());
            item.setCheckPoint(source.getCheckPoint());
            item.setSpecification(source.getSpecification());
            item.setExpectedValue(source.getExpectedValue());
            item.setAcceptanceCriterion(source.getAcceptanceCriterion());
            item.setTolerance(source.getTolerance());
            item.setPhotosRequired(source.isPhotosRequired());
            item.setPriority(source.getPriority() != null ? source.getPriority() : "medium");
            template.addItem(item);
        }

        ChecklistTemplate saved = templateRepo.saveAndFlush(template);
        log.info("Adopted starter checklist for trade {} as template {}", trade, saved.getId());
        return mapper.toDto(saved);
    }

    /**
     * The check items a new inspection of this trade starts with, copied from the
     * tenant's active template for it.
     *
     * <p>A copy, not a reference: an inspection carried out in March records the
     * criteria that were in force in March, and a QA engineer tightening a tolerance
     * in April must not rewrite what was signed off. The copied items start
     * {@code PENDING} with no measurement, which is what an inspector fills in.
     *
     * @param trade The inspection's trade, may be null.
     * @return The instantiated check items, or an empty list when the trade is null,
     *         or the tenant has no active template for it. An empty list is a normal
     *         outcome, not an error: an inspection may be run without a template.
     */
    @Transactional(readOnly = true)
    public List<InspectionCheckItem> instantiateFor(InspectionTrade trade) {
        if (trade == null) {
            return List.of();
        }
        return templateRepo.findByTradeAndActiveTrue(trade)
                .map(template -> template.getItems().stream()
                        .map(ChecklistTemplateService::toCheckItem)
                        .toList())
                .orElseGet(List::of);
    }

    private static InspectionCheckItem toCheckItem(ChecklistTemplateItem source) {
        InspectionCheckItem item = new InspectionCheckItem();
        item.setCategory(source.getCategory());
        item.setCheckPoint(source.getCheckPoint());
        item.setSpecification(source.getSpecification());
        item.setExpectedValue(source.getExpectedValue());
        item.setAcceptanceCriterion(source.getAcceptanceCriterion());
        item.setTolerance(source.getTolerance());
        item.setPhotosRequired(source.isPhotosRequired());
        item.setPriority(source.getPriority() != null ? source.getPriority() : "medium");
        item.setStatus(CheckItemStatus.PENDING);
        return item;
    }

    private ChecklistTemplate require(UUID id) {
        return templateRepo.findByIdScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Checklist template with ID " + id + " was not found"));
    }

    private void requireTradeIsFree(InspectionTrade trade) {
        if (templateRepo.existsByTrade(trade)) {
            throw new DuplicateResourceException(
                    "This organization already has a checklist template for trade "
                            + trade.getValue() + ". Edit that template instead of defining a second one.");
        }
    }

    private void apply(ChecklistTemplate template, ChecklistTemplateRequest req) {
        template.setName(req.name());
        template.setDescription(req.description());
        template.setActive(req.active() == null || req.active());
        for (ChecklistTemplateItemRequest source : req.items()) {
            ChecklistTemplateItem item = new ChecklistTemplateItem();
            item.setCategory(source.category());
            item.setCheckPoint(source.checkPoint());
            item.setSpecification(source.specification());
            item.setExpectedValue(source.expectedValue());
            item.setAcceptanceCriterion(source.acceptanceCriterion());
            item.setTolerance(source.tolerance());
            item.setPhotosRequired(source.photosRequired());
            item.setPriority(source.priority() != null ? source.priority() : "medium");
            template.addItem(item);
        }
    }
}
