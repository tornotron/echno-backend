package org.tornotron.echno_backend.modules.workprogress.billing.service;

import static org.tornotron.echno_backend.modules.workprogress.billing.service.BillingSupport.CERTIFIED;
import static org.tornotron.echno_backend.modules.workprogress.billing.service.BillingSupport.trimToNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.AdjustmentEffect;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillStatus;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillingModel;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.ContractBill;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.ContractBoqItem;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.ContractDeductionRule;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.ContractMilestoneRequirement;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.DeductionBasis;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.DeductionKind;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.RequirementStatus;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BillSummaryDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BillingMilestoneDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BillingOverviewDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BoqItemDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BoqItemRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.ContractBillingDetailDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.ContractBillingSummaryDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.DeductionRuleDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.DeductionRuleRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.MilestoneRequirementDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.MilestoneRequirementRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.repository.ContractBillRepository;
import org.tornotron.echno_backend.modules.workprogress.billing.repository.ContractBoqItemRepository;
import org.tornotron.echno_backend.modules.workprogress.billing.repository.ContractDeductionRuleRepository;
import org.tornotron.echno_backend.modules.workprogress.billing.repository.ContractMilestoneRequirementRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.subcontract.ContractMilestone;
import org.tornotron.echno_backend.subcontract.SubContract;
import org.tornotron.echno_backend.wbs.WbsElement;
import org.tornotron.echno_backend.wbs.WbsElementRepository;

/**
 * The commercial set-up of a contract for billing (its BOQ, deduction rules and milestone
 * requirements) and the billing home page: every contract with its billing model, what is
 * certified so far and its open bill. Every read is scoped to the caller's organization, so a
 * foreign id reads as absent.
 */
@Service
public class ContractBillingService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ContractBoqItemRepository boqItems;
    private final ContractDeductionRuleRepository rules;
    private final ContractMilestoneRequirementRepository requirements;
    private final ContractBillRepository bills;
    private final WbsElementRepository elements;
    private final BillingSupport support;
    private final TenantEntityHelper tenantEntityHelper;

    public ContractBillingService(ContractBoqItemRepository boqItems, ContractDeductionRuleRepository rules,
                                  ContractMilestoneRequirementRepository requirements, ContractBillRepository bills,
                                  WbsElementRepository elements, BillingSupport support,
                                  TenantEntityHelper tenantEntityHelper) {
        this.boqItems = boqItems;
        this.rules = rules;
        this.requirements = requirements;
        this.bills = bills;
        this.elements = elements;
        this.support = support;
        this.tenantEntityHelper = tenantEntityHelper;
    }

    // ---------------------------------------------------------------- home page

    @Transactional(readOnly = true)
    public BillingOverviewDto overview() {
        Long orgId = orgId();
        Map<BillStatus, Long> counts = new HashMap<>();
        BigDecimal certified = BillMath.zero();
        BigDecimal approvedNet = BillMath.zero();
        for (Object[] row : bills.statusTotals(orgId)) {
            BillStatus status = (BillStatus) row[0];
            counts.put(status, ((Number) row[1]).longValue());
            if (CERTIFIED.contains(status)) {
                certified = certified.add((BigDecimal) row[2]);
            }
            if (status == BillStatus.APPROVED) {
                approvedNet = approvedNet.add((BigDecimal) row[3]);
            }
        }
        long open = counts.entrySet().stream().filter(e -> e.getKey().isOpen()).mapToLong(Map.Entry::getValue).sum();
        return new BillingOverviewDto(open, counts.getOrDefault(BillStatus.DRAFT, 0L),
                counts.getOrDefault(BillStatus.SUBMITTED, 0L), counts.getOrDefault(BillStatus.VERIFIED, 0L),
                counts.getOrDefault(BillStatus.CERTIFIED, 0L), counts.getOrDefault(BillStatus.RETURNED, 0L),
                counts.getOrDefault(BillStatus.APPROVED, 0L), BillMath.money(certified), BillMath.money(approvedNet));
    }

    @Transactional(readOnly = true)
    public Page<ContractBillingSummaryDto> contracts(Long projectId, int page, int size) {
        Long orgId = orgId();
        PageRequest request = PageRequest.of(page, size);
        Page<SubContract> contracts = projectId == null
                ? bills.contracts(orgId, request)
                : bills.contractsOfProject(orgId, projectId, request);
        if (contracts.isEmpty()) {
            return contracts.map(contract -> summary(contract, List.of()));
        }
        Map<Long, List<ContractBill>> byContract = new HashMap<>();
        for (ContractBill bill : bills.findForContracts(orgId, contracts.map(SubContract::getId).toList())) {
            byContract.computeIfAbsent(bill.getSubContractId(), id -> new ArrayList<>()).add(bill);
        }
        return contracts.map(contract -> summary(contract, byContract.getOrDefault(contract.getId(), List.of())));
    }

    @Transactional(readOnly = true)
    public ContractBillingDetailDto contract(Long subContractId) {
        Long orgId = orgId();
        SubContract contract = support.requireContract(subContractId, orgId);
        List<ContractBill> contractBills = bills.findForContract(orgId, subContractId);
        List<BoqItemDto> items = boqDtos(contract, orgId);
        BigDecimal boqTotal = items.stream().map(BoqItemDto::amount).reduce(BillMath.zero(), BigDecimal::add);
        return new ContractBillingDetailDto(summary(contract, contractBills), contract.getRetentionPercentage(),
                contract.getMobilizationAdvance(), items, boqTotal, ruleDtos(contract, orgId),
                milestoneDtos(contract, orgId),
                contractBills.stream().map(bill -> summaryOf(bill, contract)).toList());
    }

    // ---------------------------------------------------------------- BOQ

    @Transactional(readOnly = true)
    public List<BoqItemDto> listBoq(Long subContractId) {
        Long orgId = orgId();
        return boqDtos(support.requireContract(subContractId, orgId), orgId);
    }

    @Transactional
    public BoqItemDto addBoqItem(Long subContractId, BoqItemRequest req) {
        Organization org = tenantEntityHelper.resolveCurrentOrganization();
        SubContract contract = support.requireContract(subContractId, org.getId());
        String code = req.itemCode().trim();
        if (boqItems.existsCode(org.getId(), subContractId, code)) {
            throw new InvalidRequestException("Item code " + code + " is already on this contract's BOQ");
        }
        ContractBoqItem item = new ContractBoqItem();
        item.setOrganization(org);
        item.setSubContractId(subContractId);
        applyBoq(item, req, contract, org.getId());
        item.setItemCode(code);
        ContractBoqItem saved = boqItems.save(item);
        return boqDto(saved, BigDecimal.ZERO, false, wbsCodes(List.of(saved), org.getId()));
    }

    @Transactional
    public BoqItemDto updateBoqItem(UUID itemId, BoqItemRequest req) {
        Long orgId = orgId();
        ContractBoqItem item = boqItems.findScoped(itemId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("BOQ item not found: " + itemId));
        SubContract contract = support.requireContract(item.getSubContractId(), orgId);
        String code = req.itemCode().trim();
        if (!code.equals(item.getItemCode()) && boqItems.existsCode(orgId, item.getSubContractId(), code)) {
            throw new InvalidRequestException("Item code " + code + " is already on this contract's BOQ");
        }
        BigDecimal certified = certifiedQuantities(orgId, item.getSubContractId()).getOrDefault(item.getId(), BigDecimal.ZERO);
        if (req.contractQuantity().compareTo(certified) < 0) {
            throw new InvalidRequestException("Item " + item.getItemCode() + " already has " + BillMath.quantity(certified)
                    + " " + item.getUnit() + " certified; the contract quantity cannot go below that");
        }
        applyBoq(item, req, contract, orgId);
        item.setItemCode(code);
        ContractBoqItem saved = boqItems.save(item);
        return boqDto(saved, certified, bills.boqItemInUse(orgId, itemId), wbsCodes(List.of(saved), orgId));
    }

    @Transactional
    public void deleteBoqItem(UUID itemId) {
        Long orgId = orgId();
        ContractBoqItem item = boqItems.findScoped(itemId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("BOQ item not found: " + itemId));
        if (bills.boqItemInUse(orgId, itemId)) {
            throw new InvalidRequestException("Item " + item.getItemCode()
                    + " is on a bill, so it stays on the BOQ; change its description or rate instead");
        }
        boqItems.delete(item);
    }

    private void applyBoq(ContractBoqItem item, BoqItemRequest req, SubContract contract, Long orgId) {
        item.setDescription(req.description().trim());
        item.setUnit(req.unit().trim());
        item.setContractQuantity(BillMath.quantity(req.contractQuantity()));
        item.setRate(BillMath.money(req.rate()));
        item.setAmount(BillMath.lineAmount(item.getContractQuantity(), item.getRate()));
        if (req.sortOrder() != null) {
            item.setSortOrder(req.sortOrder());
        }
        if (req.wbsElementId() != null) {
            WbsElement activity = elements.findByIdAndOrganization_Id(req.wbsElementId(), orgId)
                    .orElseThrow(() -> new ResourceNotFoundException("Activity not found: " + req.wbsElementId()));
            if (contract.getProjectId() == null || !contract.getProjectId().equals(activity.getProject().getId())) {
                throw new InvalidRequestException("Activity " + activity.getWbsCode()
                        + " is not part of this contract's project");
            }
        }
        item.setWbsElementId(req.wbsElementId());
    }

    private List<BoqItemDto> boqDtos(SubContract contract, Long orgId) {
        List<ContractBoqItem> items = boqItems.findForContract(orgId, contract.getId());
        Map<UUID, BigDecimal> certified = certifiedQuantities(orgId, contract.getId());
        Map<Long, String> codes = wbsCodes(items, orgId);
        return items.stream()
                .map(item -> boqDto(item, certified.getOrDefault(item.getId(), BigDecimal.ZERO),
                        bills.boqItemInUse(orgId, item.getId()), codes))
                .toList();
    }

    private Map<UUID, BigDecimal> certifiedQuantities(Long orgId, Long contractId) {
        Map<UUID, BigDecimal> certified = new HashMap<>();
        for (Object[] row : bills.certifiedQuantities(orgId, contractId, CERTIFIED)) {
            if (row[1] != null) {
                certified.put((UUID) row[0], (BigDecimal) row[1]);
            }
        }
        return certified;
    }

    private Map<Long, String> wbsCodes(List<ContractBoqItem> items, Long orgId) {
        Map<Long, String> codes = new HashMap<>();
        for (ContractBoqItem item : items) {
            Long id = item.getWbsElementId();
            if (id != null && !codes.containsKey(id)) {
                elements.findByIdAndOrganization_Id(id, orgId).ifPresent(e -> codes.put(id, e.getWbsCode()));
            }
        }
        return codes;
    }

    private static BoqItemDto boqDto(ContractBoqItem item, BigDecimal certified, boolean inUse, Map<Long, String> codes) {
        return new BoqItemDto(item.getId(), item.getSubContractId(), item.getItemCode(), item.getDescription(),
                item.getUnit(), item.getContractQuantity(), item.getRate(), item.getAmount(), item.getWbsElementId(),
                codes.get(item.getWbsElementId()), item.getSortOrder(), BillMath.quantity(certified), inUse);
    }

    // ---------------------------------------------------------------- deduction rules

    @Transactional(readOnly = true)
    public List<DeductionRuleDto> listRules(Long subContractId) {
        Long orgId = orgId();
        return ruleDtos(support.requireContract(subContractId, orgId), orgId);
    }

    @Transactional
    public DeductionRuleDto addRule(Long subContractId, DeductionRuleRequest req) {
        Organization org = tenantEntityHelper.resolveCurrentOrganization();
        support.requireContract(subContractId, org.getId());
        ContractDeductionRule rule = new ContractDeductionRule();
        rule.setOrganization(org);
        rule.setSubContractId(subContractId);
        applyRule(rule, req);
        ContractDeductionRule saved = rules.save(rule);
        return ruleDto(saved, BillMath.zero());
    }

    @Transactional
    public DeductionRuleDto updateRule(UUID ruleId, DeductionRuleRequest req) {
        Long orgId = orgId();
        ContractDeductionRule rule = rules.findScoped(ruleId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Deduction rule not found: " + ruleId));
        applyRule(rule, req);
        ContractDeductionRule saved = rules.save(rule);
        return ruleDto(saved, appliedByRule(orgId, rule.getSubContractId()).getOrDefault(ruleId, BillMath.zero()));
    }

    @Transactional
    public void deleteRule(UUID ruleId) {
        Long orgId = orgId();
        ContractDeductionRule rule = rules.findScoped(ruleId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Deduction rule not found: " + ruleId));
        if (bills.ruleInUse(orgId, ruleId)) {
            throw new InvalidRequestException("Rule \"" + rule.getLabel()
                    + "\" has been applied to a certified bill; disable it instead of deleting it");
        }
        rules.delete(rule);
    }

    /**
     * Creates the retention rule from the contract record when billing starts on a contract with no
     * rules. The caller runs it for a contract's first bill only, so a rule the project manager has
     * deleted is not brought back by the next bill.
     */
    void seedRulesIfNone(SubContract contract, Organization org) {
        if (rules.existsForContract(org.getId(), contract.getId())) {
            return;
        }
        BigDecimal retention = contract.getRetentionPercentage();
        if (retention == null || retention.signum() <= 0 || retention.compareTo(HUNDRED) > 0) {
            return;
        }
        ContractDeductionRule rule = new ContractDeductionRule();
        rule.setOrganization(org);
        rule.setSubContractId(contract.getId());
        rule.setKind(DeductionKind.RETENTION);
        rule.setLabel("Retention " + retention.stripTrailingZeros().toPlainString() + "%");
        rule.setEffect(AdjustmentEffect.DEDUCT);
        rule.setBasis(DeductionBasis.PERCENT);
        rule.setRate(retention.setScale(3, RoundingMode.HALF_UP));
        rule.setEnabled(true);
        rules.save(rule);
    }

    private static void applyRule(ContractDeductionRule rule, DeductionRuleRequest req) {
        if (req.basis() == DeductionBasis.PERCENT) {
            if (req.rate() == null) {
                throw new InvalidRequestException("A percent rule needs its rate");
            }
            if (req.fixedAmount() != null) {
                throw new InvalidRequestException("A percent rule has a rate, not a fixed amount");
            }
        } else {
            if (req.fixedAmount() == null) {
                throw new InvalidRequestException("A fixed rule needs its amount");
            }
            if (req.rate() != null) {
                throw new InvalidRequestException("A fixed rule has an amount, not a rate");
            }
        }
        rule.setKind(req.kind());
        rule.setLabel(req.label().trim());
        rule.setEffect(req.effect() != null ? req.effect() : req.kind().defaultEffect());
        rule.setBasis(req.basis());
        rule.setRate(req.rate() == null ? null : req.rate().setScale(3, RoundingMode.HALF_UP));
        rule.setFixedAmount(req.fixedAmount() == null ? null : BillMath.money(req.fixedAmount()));
        rule.setCapAmount(req.capAmount() == null ? null : BillMath.money(req.capAmount()));
        rule.setEnabled(req.enabled() == null || req.enabled());
        if (req.sortOrder() != null) {
            rule.setSortOrder(req.sortOrder());
        }
    }

    private List<DeductionRuleDto> ruleDtos(SubContract contract, Long orgId) {
        Map<UUID, BigDecimal> applied = appliedByRule(orgId, contract.getId());
        return rules.findForContract(orgId, contract.getId()).stream()
                .map(rule -> ruleDto(rule, applied.getOrDefault(rule.getId(), BillMath.zero())))
                .toList();
    }

    Map<UUID, BigDecimal> appliedByRule(Long orgId, Long contractId) {
        Map<UUID, BigDecimal> applied = new HashMap<>();
        for (Object[] row : bills.appliedByRule(orgId, contractId, CERTIFIED)) {
            applied.put((UUID) row[0], BillMath.money((BigDecimal) row[1]));
        }
        return applied;
    }

    private static DeductionRuleDto ruleDto(ContractDeductionRule rule, BigDecimal applied) {
        return new DeductionRuleDto(rule.getId(), rule.getSubContractId(), rule.getKind(), rule.getLabel(),
                rule.getEffect(), rule.getBasis(), rule.getRate(), rule.getFixedAmount(), rule.getCapAmount(),
                rule.isEnabled(), rule.getSortOrder(), applied);
    }

    // ---------------------------------------------------------------- milestone requirements

    @Transactional(readOnly = true)
    public List<MilestoneRequirementDto> listRequirements(Long subContractId, Long milestoneId) {
        Long orgId = orgId();
        BillingSupport.requireMilestone(support.requireContract(subContractId, orgId), milestoneId);
        return requirements.findForMilestones(orgId, List.of(milestoneId)).stream()
                .map(ContractBillingService::requirementDto).toList();
    }

    @Transactional
    public MilestoneRequirementDto addRequirement(Long subContractId, Long milestoneId, MilestoneRequirementRequest req) {
        Organization org = tenantEntityHelper.resolveCurrentOrganization();
        BillingSupport.requireMilestone(support.requireContract(subContractId, org.getId()), milestoneId);
        ContractMilestoneRequirement requirement = new ContractMilestoneRequirement();
        requirement.setOrganization(org);
        requirement.setSubContractId(subContractId);
        requirement.setContractMilestoneId(milestoneId);
        applyRequirement(requirement, req);
        return requirementDto(requirements.save(requirement));
    }

    @Transactional
    public MilestoneRequirementDto updateRequirement(UUID requirementId, MilestoneRequirementRequest req) {
        ContractMilestoneRequirement requirement = requireRequirement(requirementId);
        applyRequirement(requirement, req);
        return requirementDto(requirements.save(requirement));
    }

    @Transactional
    public void deleteRequirement(UUID requirementId) {
        requirements.delete(requireRequirement(requirementId));
    }

    private ContractMilestoneRequirement requireRequirement(UUID requirementId) {
        return requirements.findScoped(requirementId, orgId())
                .orElseThrow(() -> new ResourceNotFoundException("Milestone requirement not found: " + requirementId));
    }

    private static void applyRequirement(ContractMilestoneRequirement requirement, MilestoneRequirementRequest req) {
        requirement.setTitle(req.title().trim());
        requirement.setType(req.type());
        requirement.setDescription(trimToNull(req.description()));
        requirement.setMandatory(req.mandatory() == null || req.mandatory());
        requirement.setDueDate(req.dueDate());
        requirement.setStatus(req.status() != null ? req.status() : RequirementStatus.PENDING);
        requirement.setRemarks(trimToNull(req.remarks()));
        if (req.sortOrder() != null) {
            requirement.setSortOrder(req.sortOrder());
        }
    }

    static MilestoneRequirementDto requirementDto(ContractMilestoneRequirement r) {
        return new MilestoneRequirementDto(r.getId(), r.getSubContractId(), r.getContractMilestoneId(), r.getTitle(),
                r.getType(), r.getDescription(), r.isMandatory(), r.getDueDate(), r.getStatus(), r.getRemarks(),
                r.getSortOrder());
    }

    private List<BillingMilestoneDto> milestoneDtos(SubContract contract, Long orgId) {
        List<ContractMilestone> milestones = contract.getMilestones().stream()
                .sorted(Comparator.comparing(ContractMilestone::getTargetDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ContractMilestone::getId))
                .toList();
        if (milestones.isEmpty()) {
            return List.of();
        }
        Map<Long, List<MilestoneRequirementDto>> byMilestone = new HashMap<>();
        for (ContractMilestoneRequirement r : requirements.findForMilestones(orgId,
                milestones.stream().map(ContractMilestone::getId).toList())) {
            byMilestone.computeIfAbsent(r.getContractMilestoneId(), id -> new ArrayList<>()).add(requirementDto(r));
        }
        Map<Long, BigDecimal> certified = new HashMap<>();
        for (Object[] row : bills.certifiedPercentByMilestone(orgId, contract.getId(), CERTIFIED)) {
            certified.put((Long) row[0], (BigDecimal) row[1]);
        }
        return milestones.stream().map(m -> new BillingMilestoneDto(m.getId(), m.getName(), m.getDescription(),
                m.getTargetDate(), m.getCompletionDate(), m.getStatus(), m.getPaymentPercentage(),
                BillMath.milestoneValue(m.getAmount(), contract.getContractValue(), m.getPaymentPercentage()),
                certified.getOrDefault(m.getId(), BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP),
                byMilestone.getOrDefault(m.getId(), List.of()))).toList();
    }

    // ---------------------------------------------------------------- summaries

    ContractBillingSummaryDto summary(SubContract contract, List<ContractBill> contractBills) {
        BillingModel model = null;
        int count = 0;
        int approved = 0;
        BigDecimal certified = BillMath.zero();
        BigDecimal net = BillMath.zero();
        ContractBill open = null;
        LocalDateTime lastApproved = null;
        for (ContractBill bill : contractBills) {
            if (bill.getStatus() == BillStatus.CANCELLED) {
                continue;
            }
            count++;
            model = bill.getBillingModel();
            if (bill.getStatus().isOpen() && open == null) {
                open = bill;
            }
            if (bill.getStatus().isCertified() && bill.getGrossCertified() != null) {
                certified = certified.add(bill.getGrossCertified());
            }
            if (bill.getStatus() == BillStatus.APPROVED) {
                approved++;
                if (bill.getNetPayable() != null) {
                    net = net.add(bill.getNetPayable());
                }
                if (lastApproved == null || (bill.getApprovedAt() != null && bill.getApprovedAt().isAfter(lastApproved))) {
                    lastApproved = bill.getApprovedAt();
                }
            }
        }
        BigDecimal value = contract.getContractValue();
        BigDecimal billedPercent = value != null && value.signum() > 0
                ? certified.multiply(HUNDRED).divide(value, 2, RoundingMode.HALF_UP)
                : null;
        return new ContractBillingSummaryDto(contract.getId(), trimToNull(contract.getContractId()),
                contract.getContractName(), contract.getContractorName(), contract.getProjectId(),
                contract.getProjectName(), value, contract.getStatus(), model, count, approved, certified, net,
                billedPercent, open == null ? null : summaryOf(open, contract), lastApproved);
    }

    static BillSummaryDto summaryOf(ContractBill bill, SubContract contract) {
        String milestoneName = bill.getContractMilestoneId() == null ? null : contract.getMilestones().stream()
                .filter(m -> m.getId().equals(bill.getContractMilestoneId()))
                .map(ContractMilestone::getName).findFirst().orElse(null);
        return new BillSummaryDto(bill.getId(), bill.getSubContractId(), contract.getContractName(),
                contract.getContractorName(), bill.getProjectId(), contract.getProjectName(), bill.getBillNumber(),
                bill.getBillingModel(), bill.getStatus(), bill.getPeriodFrom(), bill.getPeriodTo(),
                bill.getContractMilestoneId(), milestoneName, grossClaimed(bill), bill.getGrossCertified(),
                bill.getNetPayable(), bill.getSubmittedAt(), bill.getApprovedAt(), bill.getCreatedAt());
    }

    /** The amount a bill claims: claimed quantities at their rates, or the claimed percent of the milestone. */
    static BigDecimal grossClaimed(ContractBill bill) {
        if (bill.getBillingModel() == BillingModel.MILESTONE) {
            return bill.getMilestoneValue() == null || bill.getClaimedPercent() == null
                    ? BillMath.zero()
                    : BillMath.percentOf(bill.getMilestoneValue(), bill.getClaimedPercent());
        }
        return bill.getLines().stream()
                .map(line -> BillMath.lineAmount(line.getClaimedQuantity(), line.getRate()))
                .reduce(BillMath.zero(), BigDecimal::add);
    }

    private Long orgId() {
        return tenantEntityHelper.resolveCurrentOrganization().getId();
    }
}
