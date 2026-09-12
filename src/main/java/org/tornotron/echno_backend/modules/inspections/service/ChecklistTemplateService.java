package org.tornotron.echno_backend.modules.inspections.service;

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
import org.tornotron.echno_backend.modules.inspections.CheckItemStatus;
import org.tornotron.echno_backend.modules.inspections.domain.OrgTrade;
import org.tornotron.echno_backend.modules.inspections.domain.ChecklistTemplate;
import org.tornotron.echno_backend.modules.inspections.domain.ChecklistTemplateItem;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionCheckItem;
import org.tornotron.echno_backend.modules.inspections.domain.StarterChecklistTemplate;
import org.tornotron.echno_backend.modules.inspections.domain.StarterChecklistTemplateItem;
import org.tornotron.echno_backend.modules.inspections.dtos.ChecklistTemplateDto;
import org.tornotron.echno_backend.modules.inspections.dtos.ChecklistTemplateItemRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.ChecklistTemplateRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.StarterChecklistTemplateDto;
import org.tornotron.echno_backend.modules.inspections.mapper.ChecklistTemplateMapper;
import org.tornotron.echno_backend.modules.inspections.repositories.ChecklistTemplateRepository;
import org.tornotron.echno_backend.modules.inspections.repositories.ChecklistTemplateSpecifications;
import org.tornotron.echno_backend.modules.inspections.repositories.StarterChecklistTemplateRepository;

import org.tornotron.echno_backend.project.enums.ProjectType;

import java.util.List;
import java.util.Objects;
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
    private final TradeService tradeService;
    private final ElementTypeService elementTypeService;

    @Transactional(readOnly = true)
    public ChecklistTemplateDto findById(UUID id) {
        return mapper.toDto(require(id));
    }

    @Transactional(readOnly = true)
    public Page<ChecklistTemplateDto> findAll(String trade, UUID tradeId, Boolean active, Pageable pageable) {
        return templateRepo.findAll(ChecklistTemplateSpecifications.withFilters(trade, tradeId, active), pageable)
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
    /**
     * The templates to suggest for an element of the given type on a project of the given type:
     * active templates whose applicability is unset or names the value. Both parameters are
     * optional; with neither, every active template is returned. Suggestion only, so the
     * filtering happens in memory over the tenant's own short list.
     */
    @Transactional(readOnly = true)
    public List<ChecklistTemplateDto> findApplicable(String elementType, ProjectType projectType) {
        String element = elementType == null || elementType.isBlank() ? null : elementType.trim().toLowerCase();
        return templateRepo.findAll(ChecklistTemplateSpecifications.withFilters(null, null, true)).stream()
                .filter(t -> element == null || admits(t.getApplicableElementTypes(), element))
                .filter(t -> projectType == null || admits(t.getApplicableProjectTypes(), projectType.name()))
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(mapper::toDto)
                .toList();
    }

    private static boolean admits(List<String> applicable, String value) {
        return applicable == null || applicable.isEmpty() || applicable.contains(value);
    }

    @Transactional
    public ChecklistTemplateDto create(ChecklistTemplateRequest req) {
        OrgTrade trade = requireTrade(req.trade(), req.tradeId());
        requireTradeIsFree(trade);

        ChecklistTemplate template = new ChecklistTemplate();
        setTrade(template, trade);
        template.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        apply(template, req);

        ChecklistTemplate saved = templateRepo.saveAndFlush(template);
        log.info("Created checklist template {} for trade {}", saved.getId(), trade.getCode());
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
        if (req.trade() != null || req.tradeId() != null) {
            OrgTrade requested = tradeService.resolve(req.trade(), req.tradeId());
            if (!Objects.equals(requested.getId(), template.getTradeRef().getId())) {
                throw new InvalidRequestException(
                        "Checklist template " + id + " covers trade " + template.getTradeRef().getCode()
                                + " and cannot be moved to " + requested.getCode()
                                + ". The trade is fixed when the template is created.");
            }
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
        return starterRepo.findByActiveTrueOrderByTradeCodeAsc().stream()
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
    public ChecklistTemplateDto adoptStarter(String tradeCode) {
        StarterChecklistTemplate starter = starterRepo.findByTradeCodeAndActiveTrue(
                        tradeCode == null ? "" : tradeCode.trim().toLowerCase())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No starter checklist is available for trade " + tradeCode));
        OrgTrade trade = tradeService.resolve(starter.getTradeCode(), null);
        requireTradeIsFree(trade);

        ChecklistTemplate template = new ChecklistTemplate();
        setTrade(template, trade);
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
        log.info("Adopted starter checklist for trade {} as template {}", trade.getCode(), saved.getId());
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
    public List<InspectionCheckItem> instantiateFor(OrgTrade trade) {
        if (trade == null) {
            return List.of();
        }
        return templateRepo.findByTradeRefAndActiveTrue(trade)
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

    private OrgTrade requireTrade(String slug, UUID tradeId) {
        if (tradeId == null && (slug == null || slug.isBlank())) {
            throw new InvalidRequestException("A checklist template needs a trade: send trade or tradeId.");
        }
        return tradeService.resolve(slug, tradeId);
    }

    /** Sets the org trade row and keeps the legacy enum column in step for the shim. */
    @SuppressWarnings("deprecation")
    private static void setTrade(ChecklistTemplate template, OrgTrade trade) {
        template.setTradeRef(trade);
        template.setTrade(trade.legacyTrade());
    }

    private void requireTradeIsFree(OrgTrade trade) {
        if (templateRepo.existsByTradeRef(trade)) {
            throw new DuplicateResourceException(
                    "This organization already has a checklist template for trade "
                            + trade.getCode() + ". Edit that template instead of defining a second one.");
        }
    }

    /** Lowercases, de-duplicates and checks each code against the org's element types. */
    private List<String> normaliseElementTypes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return null;
        }
        List<String> cleaned = codes.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(c -> c.trim().toLowerCase())
                .distinct()
                .toList();
        for (String code : cleaned) {
            if (!elementTypeService.isKnownCode(code)) {
                throw new InvalidRequestException("Unknown element type: " + code
                        + ". Define it under the organization's element types first.");
            }
        }
        return cleaned.isEmpty() ? null : cleaned;
    }

    private void apply(ChecklistTemplate template, ChecklistTemplateRequest req) {
        template.setName(req.name());
        template.setDescription(req.description());
        template.setActive(req.active() == null || req.active());
        template.setApplicableElementTypes(normaliseElementTypes(req.applicableElementTypes()));
        template.setApplicableProjectTypes(req.applicableProjectTypes() == null || req.applicableProjectTypes().isEmpty()
                ? null
                : req.applicableProjectTypes().stream().distinct().map(Enum::name).toList());
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
