package org.tornotron.echno_backend.modules.workprogress.billing.service;

import static org.tornotron.echno_backend.modules.workprogress.billing.service.BillingSupport.CERTIFIED;
import static org.tornotron.echno_backend.modules.workprogress.billing.service.BillingSupport.trimToNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.dto.AttachmentDocumentMetadataDto;
import org.tornotron.echno_backend.common.dto.PresignedUpload;
import org.tornotron.echno_backend.common.dto.RegisterUploadRequest;
import org.tornotron.echno_backend.common.dto.UploadRequest;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.AdjustmentSource;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillEventType;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillLineStatus;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillStatus;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillingModel;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.ContractBill;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.ContractBillAdjustment;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.ContractBillDocument;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.ContractBillEvent;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.ContractBillLine;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.ContractBoqItem;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.ContractDeductionRule;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.ContractMilestoneRequirement;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.DeductionBasis;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BillAdjustmentDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BillCommentRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BillDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BillEventDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BillLineDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BillSummaryDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.ClaimLineRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.CreateBillRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.ManualAdjustmentRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.ManualAdjustmentsRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.MeasurementLineRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.MeasurementRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.MilestoneRequirementDto;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.UpdateBillRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.repository.ContractBillEventRepository;
import org.tornotron.echno_backend.modules.workprogress.billing.repository.ContractBillRepository;
import org.tornotron.echno_backend.modules.workprogress.billing.repository.ContractBoqItemRepository;
import org.tornotron.echno_backend.modules.workprogress.billing.repository.ContractDeductionRuleRepository;
import org.tornotron.echno_backend.modules.workprogress.billing.repository.ContractMilestoneRequirementRepository;
import org.tornotron.echno_backend.modules.workprogress.time.WorkProgressClock;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.payable.PayableService;
import org.tornotron.echno_backend.payable.dto.PayableCreationDto;
import org.tornotron.echno_backend.payable.dto.PayableDto;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.subcontract.ContractMilestone;
import org.tornotron.echno_backend.subcontract.SubContract;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.wbs.WbsElement;
import org.tornotron.echno_backend.wbs.WbsElementRepository;

/**
 * Running account and milestone bills, from the claim to final approval
 * ({@code docs/specs/2026-10-04-ra-milestone-billing.md}).
 *
 * <p>The rules, in one place. A contract has one billing model, fixed by its first bill, and one
 * open bill at a time, so the previous certified quantities under an open bill cannot move. The
 * claim is edited while the bill is a draft or returned; the joint measurement is recorded while
 * it is submitted; manual adjustments are entered before certification; certification applies the
 * contract's rules and freezes every figure; final approval, by someone other than the certifier
 * unless a system admin, hands the net to finance as a payable. Every read is scoped to the
 * caller's organization, so a foreign id reads as absent, and every step lands on the timeline.
 */
@Service
public class ContractBillService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final String SYSTEM_ADMIN = "system-admin";

    private final ContractBillRepository bills;
    private final ContractBoqItemRepository boqItems;
    private final ContractDeductionRuleRepository rules;
    private final ContractMilestoneRequirementRepository requirements;
    private final ContractBillEventRepository events;
    private final ContractBillingService setup;
    private final BillingSupport support;
    private final WbsElementRepository elements;
    private final ProjectRepository projects;
    private final EmployeeRepository employees;
    private final PayableService payables;
    private final AttachmentService attachmentService;
    private final OrganizationSecurityService orgSecurity;
    private final TenantEntityHelper tenantEntityHelper;
    private final UserContextService userContextService;
    private final Clock clock;

    public ContractBillService(ContractBillRepository bills, ContractBoqItemRepository boqItems,
                               ContractDeductionRuleRepository rules,
                               ContractMilestoneRequirementRepository requirements,
                               ContractBillEventRepository events, ContractBillingService setup,
                               BillingSupport support, WbsElementRepository elements, ProjectRepository projects,
                               EmployeeRepository employees, PayableService payables,
                               AttachmentService attachmentService, OrganizationSecurityService orgSecurity,
                               TenantEntityHelper tenantEntityHelper, UserContextService userContextService,
                               @WorkProgressClock Clock clock) {
        this.bills = bills;
        this.boqItems = boqItems;
        this.rules = rules;
        this.requirements = requirements;
        this.events = events;
        this.setup = setup;
        this.support = support;
        this.elements = elements;
        this.projects = projects;
        this.employees = employees;
        this.payables = payables;
        this.attachmentService = attachmentService;
        this.orgSecurity = orgSecurity;
        this.tenantEntityHelper = tenantEntityHelper;
        this.userContextService = userContextService;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public Page<BillSummaryDto> list(Long projectId, Long subContractId, BillStatus status, BillingModel model,
                                     int page, int size) {
        Long orgId = orgId();
        Page<ContractBill> rows = bills.findPage(orgId, projectId, subContractId, status, model, PageRequest.of(page, size));
        Map<Long, SubContract> contracts = new HashMap<>();
        return rows.map(bill -> ContractBillingService.summaryOf(bill,
                contracts.computeIfAbsent(bill.getSubContractId(), id -> support.requireContract(id, orgId))));
    }

    @Transactional(readOnly = true)
    public BillDto get(UUID id) {
        return toDto(require(id));
    }

    @Transactional(readOnly = true)
    public List<BillEventDto> events(UUID id) {
        ContractBill bill = require(id);
        return events.findForBill(bill.getOrganization().getId(), id).stream()
                .map(e -> new BillEventDto(e.getId(), e.getType(), e.getFromStatus(), e.getToStatus(), e.getNote(),
                        e.getActorName(), e.getCreatedAt()))
                .toList();
    }

    // ---------------------------------------------------------------- the claim

    @Transactional
    public BillDto create(CreateBillRequest req) {
        Organization org = tenantEntityHelper.resolveCurrentOrganization();
        Long orgId = org.getId();
        SubContract contract = support.requireContract(req.subContractId(), orgId);
        String ref = BillingSupport.contractRef(contract);
        if (contract.getProjectId() == null) {
            throw new InvalidRequestException("Subcontract " + ref + " is not linked to a project; link it before billing");
        }
        projects.findByIdAndOrganization_Id(contract.getProjectId(), orgId)
                .orElseThrow(() -> new InvalidRequestException("The project of subcontract " + ref + " was not found"));

        List<ContractBill> existing = bills.findForContract(orgId, contract.getId());
        for (ContractBill other : existing) {
            if (other.getStatus().isOpen()) {
                throw new InvalidRequestException("Bill " + other.getBillNumber() + " is still open on this contract ("
                        + other.getStatus().name().toLowerCase() + "); approve or cancel it before opening the next");
            }
        }
        existing.stream().filter(other -> other.getStatus() != BillStatus.CANCELLED).findFirst().ifPresent(first -> {
            if (first.getBillingModel() != req.billingModel()) {
                throw new InvalidRequestException("Subcontract " + ref + " is billed by " + label(first.getBillingModel())
                        + "; a " + label(req.billingModel()) + " bill cannot be added to it");
            }
        });

        ContractBill bill = new ContractBill();
        bill.setOrganization(org);
        bill.setProjectId(contract.getProjectId());
        bill.setSubContractId(contract.getId());
        bill.setBillingModel(req.billingModel());
        bill.setStatus(BillStatus.DRAFT);
        bill.setContractorReference(trimToNull(req.contractorReference()));
        bill.setLocation(trimToNull(req.location()));
        bill.setRemarks(trimToNull(req.remarks()));
        bill.setPreparedBy(userContextService.getCurrentUserId());

        if (req.billingModel() == BillingModel.RUNNING_ACCOUNT) {
            if (req.contractMilestoneId() != null || req.claimedPercent() != null) {
                throw new InvalidRequestException("A running account bill is billed by quantity, not against a milestone");
            }
            requirePeriod(req.periodFrom(), req.periodTo(), existing, null);
            bill.setPeriodFrom(req.periodFrom());
            bill.setPeriodTo(req.periodTo());
            List<ContractBoqItem> items = boqItems.findForContract(orgId, contract.getId());
            if (items.isEmpty()) {
                throw new InvalidRequestException("Subcontract " + ref
                        + " has no bill of quantities yet; enter its BOQ before opening a running account bill");
            }
            Map<UUID, BigDecimal> previous = certifiedQuantities(orgId, contract.getId());
            int order = 0;
            for (ContractBoqItem item : items) {
                ContractBillLine line = new ContractBillLine();
                line.setBoqItemId(item.getId());
                line.setItemCode(item.getItemCode());
                line.setDescription(item.getDescription());
                line.setUnit(item.getUnit());
                line.setContractQuantity(item.getContractQuantity());
                line.setRate(item.getRate());
                line.setPreviousQuantity(BillMath.quantity(previous.getOrDefault(item.getId(), BigDecimal.ZERO)));
                line.setClaimedQuantity(BillMath.quantity(BigDecimal.ZERO));
                line.setSortOrder(order++);
                bill.addLine(line);
            }
        } else {
            if (req.periodFrom() != null || req.periodTo() != null) {
                throw new InvalidRequestException("A milestone bill is billed against its milestone, not a period");
            }
            if (req.contractMilestoneId() == null) {
                throw new InvalidRequestException("Choose the milestone this bill is for");
            }
            ContractMilestone milestone = BillingSupport.requireMilestone(contract, req.contractMilestoneId());
            BigDecimal value = BillMath.milestoneValue(milestone.getAmount(), contract.getContractValue(),
                    milestone.getPaymentPercentage());
            if (value == null) {
                throw new InvalidRequestException("Milestone \"" + milestone.getName()
                        + "\" has neither an amount nor a payment percentage of a contract value; record one first");
            }
            BigDecimal before = certifiedPercent(orgId, contract.getId(), milestone.getId());
            if (before.compareTo(HUNDRED) >= 0) {
                throw new InvalidRequestException("Milestone \"" + milestone.getName() + "\" is already fully certified");
            }
            bill.setContractMilestoneId(milestone.getId());
            bill.setMilestoneValue(value);
            if (req.claimedPercent() != null) {
                requireClaimablePercent(req.claimedPercent(), before);
                bill.setClaimedPercent(req.claimedPercent().setScale(2, RoundingMode.HALF_UP));
            }
        }

        if (existing.isEmpty()) {
            setup.seedRulesIfNone(contract, org);
        }
        int sequence = bills.maxSequence(orgId, contract.getId()) + 1;
        bill.setSequenceNo(sequence);
        bill.setBillNumber((req.billingModel() == BillingModel.RUNNING_ACCOUNT ? "RA-" : "MB-")
                + String.format("%02d", sequence));
        ContractBill saved = bills.save(bill);
        record(saved, BillEventType.CREATED, null, BillStatus.DRAFT, label(saved.getBillingModel()) + " bill "
                + saved.getBillNumber() + " opened");
        return toDto(saved);
    }

    @Transactional
    public BillDto update(UUID id, UpdateBillRequest req) {
        ContractBill bill = require(id);
        if (!bill.getStatus().isEditable()) {
            throw new InvalidRequestException("Bill " + bill.getBillNumber() + " is " + statusText(bill)
                    + "; its claim can be changed only while it is a draft or returned for correction");
        }
        Long orgId = bill.getOrganization().getId();
        if (bill.getBillingModel() == BillingModel.RUNNING_ACCOUNT) {
            if (req.claimedPercent() != null) {
                throw new InvalidRequestException("A running account bill is claimed by quantity, not by percent");
            }
            requirePeriod(req.periodFrom(), req.periodTo(), bills.findForContract(orgId, bill.getSubContractId()), bill);
            bill.setPeriodFrom(req.periodFrom());
            bill.setPeriodTo(req.periodTo());
            Map<UUID, ContractBillLine> lines = bill.getLines().stream()
                    .collect(Collectors.toMap(ContractBillLine::getId, Function.identity()));
            for (ClaimLineRequest change : req.lines() == null ? List.<ClaimLineRequest>of() : req.lines()) {
                ContractBillLine line = lines.get(change.lineId());
                if (line == null) {
                    throw new InvalidRequestException("Line " + change.lineId() + " is not on bill " + bill.getBillNumber());
                }
                BigDecimal claimed = BillMath.quantity(change.claimedQuantity());
                if (line.getPreviousQuantity().add(claimed).compareTo(line.getContractQuantity()) > 0) {
                    throw new InvalidRequestException("Item " + line.getItemCode() + ": " + line.getPreviousQuantity()
                            + " already certified plus " + claimed + " claimed passes the contract quantity of "
                            + line.getContractQuantity() + " " + line.getUnit()
                            + "; bill the excess as an extra item");
                }
                if (claimed.compareTo(line.getClaimedQuantity()) != 0) {
                    // A changed claim has not been measured: an earlier measurement of a returned
                    // bill must not be paid against the new claim.
                    line.setMeasuredQuantity(null);
                    line.setAcceptedQuantity(null);
                }
                line.setClaimedQuantity(claimed);
                line.setRemarks(trimToNull(change.remarks()));
            }
        } else {
            if (req.periodFrom() != null || req.periodTo() != null) {
                throw new InvalidRequestException("A milestone bill is billed against its milestone, not a period");
            }
            if (req.lines() != null && !req.lines().isEmpty()) {
                throw new InvalidRequestException("A milestone bill has no quantity lines");
            }
            if (req.claimedPercent() != null) {
                requireClaimablePercent(req.claimedPercent(),
                        certifiedPercent(orgId, bill.getSubContractId(), bill.getContractMilestoneId()));
            }
            BigDecimal claimed = req.claimedPercent() == null ? null : req.claimedPercent().setScale(2, RoundingMode.HALF_UP);
            if (claimed == null ? bill.getClaimedPercent() != null : claimed.compareTo(
                    bill.getClaimedPercent() == null ? BigDecimal.ZERO.setScale(2) : bill.getClaimedPercent()) != 0) {
                bill.setCertifiedPercent(null);
            }
            bill.setClaimedPercent(claimed);
        }
        bill.setContractorReference(trimToNull(req.contractorReference()));
        bill.setLocation(trimToNull(req.location()));
        bill.setRemarks(trimToNull(req.remarks()));
        ContractBill saved = bills.save(bill);
        record(saved, BillEventType.CLAIM_UPDATED, null, null, null);
        return toDto(saved);
    }

    @Transactional
    public BillDto submit(UUID id) {
        ContractBill bill = require(id);
        if (!bill.getStatus().isEditable()) {
            throw new InvalidRequestException("Bill " + bill.getBillNumber() + " is " + statusText(bill)
                    + "; only a draft or returned bill can be submitted");
        }
        if (bill.getBillingModel() == BillingModel.RUNNING_ACCOUNT) {
            if (bill.getLines().stream().noneMatch(line -> line.getClaimedQuantity().signum() > 0)) {
                throw new InvalidRequestException("Claim a quantity on at least one item before submitting");
            }
        } else if (bill.getClaimedPercent() == null || bill.getClaimedPercent().signum() <= 0) {
            throw new InvalidRequestException("Claim a percent of the milestone value before submitting");
        }
        BillStatus from = bill.getStatus();
        bill.setStatus(BillStatus.SUBMITTED);
        bill.setSubmittedBy(userContextService.getCurrentUserId());
        bill.setSubmittedAt(now());
        ContractBill saved = bills.save(bill);
        record(saved, BillEventType.SUBMITTED, from, BillStatus.SUBMITTED, null);
        return toDto(saved);
    }

    @Transactional
    public BillDto cancel(UUID id) {
        ContractBill bill = require(id);
        if (!bill.getStatus().isEditable()) {
            throw new InvalidRequestException("Bill " + bill.getBillNumber() + " is " + statusText(bill)
                    + "; only a draft or returned bill can be cancelled");
        }
        BillStatus from = bill.getStatus();
        bill.setStatus(BillStatus.CANCELLED);
        ContractBill saved = bills.save(bill);
        record(saved, BillEventType.CANCELLED, from, BillStatus.CANCELLED, null);
        return toDto(saved);
    }

    // ---------------------------------------------------------------- measurement and verification

    @Transactional
    public BillDto saveMeasurement(UUID id, MeasurementRequest req) {
        ContractBill bill = require(id);
        if (bill.getStatus() != BillStatus.SUBMITTED) {
            throw new InvalidRequestException("Bill " + bill.getBillNumber() + " is " + statusText(bill)
                    + "; the joint measurement is recorded while it is submitted");
        }
        if (req.measurementDate() != null && req.measurementDate().isAfter(LocalDate.now(clock))) {
            throw new InvalidRequestException("The measurement date " + req.measurementDate() + " is in the future");
        }
        bill.setMeasurementDate(req.measurementDate());
        bill.setMeasuredBy(trimToNull(req.measuredBy()));
        bill.setClientRepresentative(trimToNull(req.clientRepresentative()));
        if (bill.getBillingModel() == BillingModel.RUNNING_ACCOUNT) {
            if (req.certifiedPercent() != null) {
                throw new InvalidRequestException("A running account bill is measured by quantity, not by percent");
            }
            Map<UUID, ContractBillLine> lines = bill.getLines().stream()
                    .collect(Collectors.toMap(ContractBillLine::getId, Function.identity()));
            for (MeasurementLineRequest change : req.lines() == null ? List.<MeasurementLineRequest>of() : req.lines()) {
                ContractBillLine line = lines.get(change.lineId());
                if (line == null) {
                    throw new InvalidRequestException("Line " + change.lineId() + " is not on bill " + bill.getBillNumber());
                }
                BigDecimal accepted = change.acceptedQuantity() == null ? null : BillMath.quantity(change.acceptedQuantity());
                if (accepted != null && accepted.compareTo(line.getClaimedQuantity()) > 0) {
                    throw new InvalidRequestException("Item " + line.getItemCode() + ": " + accepted
                            + " accepted is more than the " + line.getClaimedQuantity() + " claimed");
                }
                line.setMeasuredQuantity(change.measuredQuantity() == null ? null : BillMath.quantity(change.measuredQuantity()));
                line.setAcceptedQuantity(accepted);
                line.setRemarks(trimToNull(change.remarks()));
            }
        } else {
            if (req.lines() != null && !req.lines().isEmpty()) {
                throw new InvalidRequestException("A milestone bill has no quantity lines");
            }
            if (req.certifiedPercent() != null && req.certifiedPercent().compareTo(bill.getClaimedPercent()) > 0) {
                throw new InvalidRequestException(req.certifiedPercent() + " percent accepted is more than the "
                        + bill.getClaimedPercent() + " percent claimed");
            }
            bill.setCertifiedPercent(req.certifiedPercent() == null ? null
                    : req.certifiedPercent().setScale(2, RoundingMode.HALF_UP));
        }
        ContractBill saved = bills.save(bill);
        record(saved, BillEventType.MEASUREMENT_SAVED, null, null, null);
        return toDto(saved);
    }

    @Transactional
    public BillDto verify(UUID id) {
        ContractBill bill = require(id);
        if (bill.getStatus() != BillStatus.SUBMITTED) {
            throw new InvalidRequestException("Bill " + bill.getBillNumber() + " is " + statusText(bill)
                    + "; only a submitted bill can be verified");
        }
        if (bill.getMeasurementDate() == null) {
            throw new InvalidRequestException("Record the date of the joint measurement before verifying");
        }
        if (bill.getBillingModel() == BillingModel.RUNNING_ACCOUNT) {
            List<String> open = new ArrayList<>();
            for (ContractBillLine line : bill.getLines()) {
                if (line.getClaimedQuantity().signum() == 0) {
                    line.setAcceptedQuantity(BillMath.quantity(BigDecimal.ZERO));
                } else if (line.getAcceptedQuantity() == null) {
                    open.add(line.getItemCode());
                } else if (line.getAcceptedQuantity().compareTo(line.getClaimedQuantity()) > 0) {
                    throw new InvalidRequestException("Item " + line.getItemCode() + ": " + line.getAcceptedQuantity()
                            + " accepted is more than the " + line.getClaimedQuantity() + " claimed; measure it again");
                }
            }
            if (!open.isEmpty()) {
                throw new InvalidRequestException("Record the accepted quantity of " + String.join(", ", open)
                        + " before verifying");
            }
            if (bill.getLines().stream().allMatch(line -> line.getAcceptedQuantity().signum() == 0)) {
                throw new InvalidRequestException("Nothing on this bill was accepted; return it for correction instead");
            }
        } else if (bill.getCertifiedPercent() == null || bill.getCertifiedPercent().signum() <= 0) {
            throw new InvalidRequestException("Record the percent of the milestone accepted before verifying, "
                    + "or return the bill for correction");
        } else if (bill.getCertifiedPercent().compareTo(bill.getClaimedPercent()) > 0) {
            throw new InvalidRequestException(bill.getCertifiedPercent() + " percent accepted is more than the "
                    + bill.getClaimedPercent() + " percent claimed; measure it again");
        }
        bill.setStatus(BillStatus.VERIFIED);
        bill.setVerifiedBy(userContextService.getCurrentUserId());
        bill.setVerifiedAt(now());
        ContractBill saved = bills.save(bill);
        record(saved, BillEventType.VERIFIED, BillStatus.SUBMITTED, BillStatus.VERIFIED, null);
        return toDto(saved);
    }

    @Transactional
    public BillDto returnForCorrection(UUID id, BillCommentRequest req) {
        ContractBill bill = require(id);
        BillStatus from = bill.getStatus();
        if (from != BillStatus.SUBMITTED && from != BillStatus.VERIFIED && from != BillStatus.CERTIFIED) {
            throw new InvalidRequestException("Bill " + bill.getBillNumber() + " is " + statusText(bill)
                    + "; only a submitted, verified or certified bill can be returned");
        }
        // A certified bill comes back unfrozen: its rule lines and totals are worked out again at
        // the next certification, against whatever the contract holds then. The timeline keeps the
        // figures it had, so the superseded certification can still be read.
        String reason = req.text().trim();
        String note = from == BillStatus.CERTIFIED
                ? reason + " (was certified at gross Rs. " + bill.getGrossCertified().toPlainString() + ", deductions Rs. "
                        + bill.getDeductionsTotal().toPlainString() + ", net Rs. " + bill.getNetPayable().toPlainString() + ")"
                : reason;
        bill.getAdjustments().removeIf(adjustment -> adjustment.getSource() == AdjustmentSource.RULE);
        bill.setGrossCertified(null);
        bill.setPreviousCertified(null);
        bill.setAdditionsTotal(null);
        bill.setDeductionsTotal(null);
        bill.setNetPayable(null);
        bill.setVerifiedBy(null);
        bill.setVerifiedAt(null);
        bill.setCertifiedBy(null);
        bill.setCertifiedAt(null);
        bill.setReturnReason(reason);
        bill.setStatus(BillStatus.RETURNED);
        ContractBill saved = bills.save(bill);
        record(saved, BillEventType.RETURNED, from, BillStatus.RETURNED, note);
        return toDto(saved);
    }

    // ---------------------------------------------------------------- certification and approval

    @Transactional
    public BillDto replaceManualAdjustments(UUID id, ManualAdjustmentsRequest req) {
        ContractBill bill = require(id);
        if (bill.getStatus().isCertified() || bill.getStatus() == BillStatus.CANCELLED) {
            throw new InvalidRequestException("Bill " + bill.getBillNumber() + " is " + statusText(bill)
                    + "; its adjustments can be changed only before certification");
        }
        bill.getAdjustments().removeIf(adjustment -> adjustment.getSource() == AdjustmentSource.MANUAL);
        int order = 100;
        for (ManualAdjustmentRequest line : req.adjustments()) {
            ContractBillAdjustment adjustment = new ContractBillAdjustment();
            adjustment.setKind(line.kind());
            adjustment.setLabel(line.label().trim());
            adjustment.setEffect(line.effect() != null ? line.effect() : line.kind().defaultEffect());
            adjustment.setBasis(DeductionBasis.FIXED);
            adjustment.setAmount(BillMath.money(line.amount()));
            adjustment.setSource(AdjustmentSource.MANUAL);
            adjustment.setSortOrder(order++);
            bill.addAdjustment(adjustment);
        }
        ContractBill saved = bills.save(bill);
        record(saved, BillEventType.ADJUSTMENTS_UPDATED, null, null, null);
        return toDto(saved);
    }

    @Transactional
    public BillDto certify(UUID id) {
        ContractBill bill = require(id);
        if (bill.getStatus() != BillStatus.VERIFIED) {
            throw new InvalidRequestException("Bill " + bill.getBillNumber() + " is " + statusText(bill)
                    + "; only a verified bill can be certified");
        }
        Long orgId = bill.getOrganization().getId();
        if (bill.getBillingModel() == BillingModel.MILESTONE) {
            List<String> open = requirements.findForMilestones(orgId, List.of(bill.getContractMilestoneId())).stream()
                    .filter(ContractMilestoneRequirement::isMandatory)
                    .filter(r -> !r.getStatus().isSatisfied())
                    .map(ContractMilestoneRequirement::getTitle)
                    .toList();
            if (!open.isEmpty()) {
                throw new InvalidRequestException("The milestone's mandatory requirements are not all met: "
                        + String.join(", ", open) + ". Complete them or mark them not applicable first");
            }
        }
        Figures figures = figures(bill, true);
        if (figures.totals().net().signum() < 0) {
            throw new InvalidRequestException("The deductions of " + figures.totals().deductions()
                    + " are more than the bill's value of " + figures.totals().gross().add(figures.totals().additions())
                    + "; review the adjustments before certifying");
        }
        int order = 0;
        for (BillMath.Adjustment line : figures.ruleLines()) {
            ContractBillAdjustment adjustment = new ContractBillAdjustment();
            adjustment.setRuleId(line.ruleId());
            adjustment.setKind(line.kind());
            adjustment.setLabel(line.label());
            adjustment.setEffect(line.effect());
            adjustment.setBasis(line.basis());
            adjustment.setRate(line.rate());
            adjustment.setAmount(line.amount());
            adjustment.setSource(AdjustmentSource.RULE);
            adjustment.setSortOrder(order++);
            bill.addAdjustment(adjustment);
        }
        bill.setGrossCertified(figures.totals().gross());
        bill.setPreviousCertified(figures.previous());
        bill.setAdditionsTotal(figures.totals().additions());
        bill.setDeductionsTotal(figures.totals().deductions());
        bill.setNetPayable(figures.totals().net());
        bill.setStatus(BillStatus.CERTIFIED);
        bill.setCertifiedBy(userContextService.getCurrentUserId());
        bill.setCertifiedAt(now());
        ContractBill saved = bills.save(bill);
        record(saved, BillEventType.CERTIFIED, BillStatus.VERIFIED, BillStatus.CERTIFIED,
                "Net payable Rs. " + figures.totals().net().toPlainString());
        return toDto(saved);
    }

    @Transactional
    public BillDto approve(UUID id) {
        ContractBill bill = require(id);
        if (bill.getStatus() != BillStatus.CERTIFIED) {
            throw new InvalidRequestException("Bill " + bill.getBillNumber() + " is " + statusText(bill)
                    + "; only a certified bill can be approved");
        }
        Long orgId = bill.getOrganization().getId();
        Long approver = userContextService.getCurrentUserId();
        if (approver == null) {
            throw new InvalidRequestException("This session resolves to no user of the organization, and an "
                    + "approval has to say who gave it");
        }
        boolean self = approver.equals(bill.getCertifiedBy());
        if (self && !orgSecurity.hasAnyOrgRoleForCurrentTenant(SYSTEM_ADMIN)) {
            throw new InvalidRequestException("You certified bill " + bill.getBillNumber()
                    + ", so its final approval has to come from someone else. A system admin may approve "
                    + "their own certification, and it is recorded as such");
        }
        Employee employee = employees.findByUserIdAndOrganizationId(approver, orgId)
                .orElseThrow(() -> new InvalidRequestException("Your account has no employee record in this "
                        + "organization, and finance records who raised a payable; ask an admin to add one"));
        SubContract contract = support.requireContract(bill.getSubContractId(), orgId);
        String note;
        if (bill.getNetPayable().signum() > 0) {
            PayableCreationDto payable = new PayableCreationDto();
            payable.setPayableNumber(BillingSupport.contractRef(contract) + "/" + bill.getBillNumber());
            payable.setContractorName(contract.getContractorName());
            payable.setContractType("SUBCONTRACTOR");
            payable.setAmountRecorded(bill.getNetPayable());
            payable.setProjectId(bill.getProjectId());
            payable.setCreatedBy(employee.getId());
            PayableDto created = payables.createPayable(payable);
            bill.setPayableId(created.getId());
            note = "Handed to finance as payable " + created.getPayableNumber();
        } else {
            note = "Nothing is payable on this bill, so no payable was raised";
        }
        bill.setStatus(BillStatus.APPROVED);
        bill.setApprovedBy(approver);
        bill.setApprovedAt(now());
        bill.setSelfApproved(self);
        ContractBill saved = bills.save(bill);
        record(saved, BillEventType.APPROVED, BillStatus.CERTIFIED, BillStatus.APPROVED,
                self ? note + ". Approved by the same system admin who certified it" : note);
        return toDto(saved);
    }

    // ---------------------------------------------------------------- notes and documents

    @Transactional
    public BillEventDto addNote(UUID id, BillCommentRequest req) {
        ContractBill bill = require(id);
        ContractBillEvent event = record(bill, BillEventType.NOTE, null, null, req.text().trim());
        return new BillEventDto(event.getId(), event.getType(), null, null, event.getNote(), event.getActorName(),
                event.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<AttachmentDto> listDocuments(UUID id) {
        require(id);
        return attachmentService.getAttachments(ContractBillDocument.ENTITY_TYPE, id);
    }

    @Transactional(readOnly = true)
    public List<PresignedUpload> presignDocuments(UUID id, List<UploadRequest> uploads) {
        requireOpenForDocuments(require(id));
        return attachmentService.presignUploads(uploads, ContractBillDocument.ownerOf(id),
                ContractBillDocument.folderFor(id));
    }

    @Transactional
    public List<AttachmentDto> registerDocuments(UUID id, String documentType, List<RegisterUploadRequest> uploads) {
        ContractBill bill = require(id);
        requireOpenForDocuments(bill);
        String type = documentType == null || documentType.isBlank() ? ContractBillDocument.OTHER : documentType.trim();
        if (!ContractBillDocument.TYPES.contains(type)) {
            throw new InvalidRequestException("Document type must be one of " + ContractBillDocument.TYPES
                    + ", but was '" + type + "'");
        }
        String folder = ContractBillDocument.folderFor(id);
        String prefix = folder + "/";
        List<RegisterUploadRequest> requests = uploads == null ? List.of() : uploads;
        for (RegisterUploadRequest upload : requests) {
            String key = upload.key();
            if (key == null || !key.startsWith(prefix) || key.contains("/../")) {
                throw new InvalidRequestException("Storage key '" + key + "' was not presigned for this bill");
            }
        }
        List<AttachmentDto> registered = new ArrayList<>();
        for (var attachment : attachmentService.registerUploads(requests, ContractBillDocument.ownerOf(id), folder)) {
            AttachmentDocumentMetadataDto metadata = new AttachmentDocumentMetadataDto();
            metadata.setDocumentType(type);
            registered.add(attachmentService.updateDocumentMetadata(attachment.getId(), metadata));
        }
        if (!registered.isEmpty()) {
            record(bill, BillEventType.DOCUMENT_ADDED, null, null, registered.size() + " " + type + " file(s): "
                    + registered.stream().map(AttachmentDto::getFileName).collect(Collectors.joining(", ")));
        }
        return registered;
    }

    @Transactional
    public void deleteDocument(UUID id, Long attachmentId) {
        ContractBill bill = require(id);
        requireOpenForDocuments(bill);
        attachmentService.deleteAttachmentOf(ContractBillDocument.ownerOf(id), attachmentId);
        record(bill, BillEventType.DOCUMENT_REMOVED, null, null, "Document " + attachmentId + " removed");
    }

    private static void requireOpenForDocuments(ContractBill bill) {
        if (!bill.getStatus().isOpen()) {
            throw new InvalidRequestException("Bill " + bill.getBillNumber() + " is " + statusText(bill)
                    + "; its documents can no longer change");
        }
    }

    // ---------------------------------------------------------------- figures

    /** What a bill comes to, worked out from its lines, its manual adjustments and the contract's rules. */
    record Figures(BigDecimal gross, BigDecimal previous, List<BillMath.Adjustment> ruleLines, BillMath.Totals totals) {
    }

    /**
     * Works out the bill. With {@code certifying} the gross is what was accepted; otherwise it is
     * accepted where measured and claimed elsewhere, as a preview. A certified bill is read back as
     * frozen and never worked out again.
     */
    Figures figures(ContractBill bill, boolean certifying) {
        Long orgId = bill.getOrganization().getId();
        BigDecimal gross;
        if (bill.getBillingModel() == BillingModel.RUNNING_ACCOUNT) {
            gross = bill.getLines().stream()
                    .map(line -> BillMath.lineAmount(thisQuantity(line, certifying), line.getRate()))
                    .reduce(BillMath.zero(), BigDecimal::add);
        } else {
            BigDecimal percent = bill.getCertifiedPercent() != null ? bill.getCertifiedPercent()
                    : certifying ? BigDecimal.ZERO : bill.getClaimedPercent();
            gross = bill.getMilestoneValue() == null || percent == null ? BillMath.zero()
                    : BillMath.percentOf(bill.getMilestoneValue(), percent);
        }
        List<BillMath.Adjustment> manual = bill.getAdjustments().stream()
                .filter(a -> a.getSource() == AdjustmentSource.MANUAL)
                .map(ContractBillService::asMath)
                .toList();
        BigDecimal base = BillMath.base(gross, manual);
        Map<UUID, BigDecimal> applied = setup.appliedByRule(orgId, bill.getSubContractId());
        List<BillMath.Rule> enabled = rules.findForContract(orgId, bill.getSubContractId()).stream()
                .filter(ContractDeductionRule::isEnabled)
                .map(r -> new BillMath.Rule(r.getId(), r.getKind(), r.getLabel(), r.getEffect(), r.getBasis(), r.getRate(),
                        r.getFixedAmount(), r.getCapAmount(), applied.get(r.getId())))
                .toList();
        List<BillMath.Adjustment> ruleLines = BillMath.applyRules(enabled, base);
        List<BillMath.Adjustment> all = new ArrayList<>(ruleLines);
        all.addAll(manual);
        BigDecimal previous = BillMath.money(bills.certifiedGross(orgId, bill.getSubContractId(), CERTIFIED));
        return new Figures(gross, previous, ruleLines, BillMath.totals(gross, all));
    }

    private static BigDecimal thisQuantity(ContractBillLine line, boolean certifying) {
        if (line.getAcceptedQuantity() != null) {
            return line.getAcceptedQuantity();
        }
        return certifying ? BigDecimal.ZERO : line.getClaimedQuantity();
    }

    private static BillMath.Adjustment asMath(ContractBillAdjustment a) {
        return new BillMath.Adjustment(a.getRuleId(), a.getKind(), a.getLabel(), a.getEffect(), a.getBasis(), a.getRate(),
                a.getAmount(), a.getSource());
    }

    // ---------------------------------------------------------------- rules

    private void requirePeriod(LocalDate from, LocalDate to, List<ContractBill> contractBills, ContractBill self) {
        if (from == null || to == null) {
            throw new InvalidRequestException("A running account bill needs the first and last day of its billing period");
        }
        if (to.isBefore(from)) {
            throw new InvalidRequestException("The billing period ends on " + to + ", before it starts on " + from);
        }
        for (ContractBill other : contractBills) {
            if (other == self || other.getStatus() == BillStatus.CANCELLED || other.getPeriodTo() == null) {
                continue;
            }
            if (!from.isAfter(other.getPeriodTo()) && !to.isBefore(other.getPeriodFrom())) {
                throw new InvalidRequestException("The period " + from + " to " + to + " overlaps bill "
                        + other.getBillNumber() + " (" + other.getPeriodFrom() + " to " + other.getPeriodTo() + ")");
            }
        }
    }

    private static void requireClaimablePercent(BigDecimal claimed, BigDecimal before) {
        BigDecimal left = HUNDRED.subtract(before);
        if (claimed.compareTo(left) > 0) {
            throw new InvalidRequestException(claimed + " percent claimed is more than the " + left.stripTrailingZeros().toPlainString()
                    + " percent of the milestone not yet certified");
        }
    }

    private BigDecimal certifiedPercent(Long orgId, Long contractId, Long milestoneId) {
        for (Object[] row : bills.certifiedPercentByMilestone(orgId, contractId, CERTIFIED)) {
            if (milestoneId.equals(row[0]) && row[1] != null) {
                return (BigDecimal) row[1];
            }
        }
        return BigDecimal.ZERO;
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

    private ContractBill require(UUID id) {
        return bills.findScoped(id, orgId())
                .orElseThrow(() -> new ResourceNotFoundException("Bill not found: " + id));
    }

    private ContractBillEvent record(ContractBill bill, BillEventType type, BillStatus from, BillStatus to, String note) {
        ContractBillEvent event = new ContractBillEvent();
        event.setOrganization(bill.getOrganization());
        event.setBillId(bill.getId());
        event.setType(type);
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setNote(note);
        Long userId = userContextService.getCurrentUserId();
        event.setActorUserId(userId);
        event.setActorName(support.nameOf(userId, bill.getOrganization().getId()));
        return events.save(event);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private Long orgId() {
        return tenantEntityHelper.resolveCurrentOrganization().getId();
    }

    private static String statusText(ContractBill bill) {
        return bill.getStatus().name().toLowerCase();
    }

    private static String label(BillingModel model) {
        return model == BillingModel.RUNNING_ACCOUNT ? "running account" : "milestone";
    }

    // ---------------------------------------------------------------- the DTO

    BillDto toDto(ContractBill bill) {
        Long orgId = bill.getOrganization().getId();
        SubContract contract = support.requireContract(bill.getSubContractId(), orgId);
        boolean frozen = bill.getStatus().isCertified();
        Figures figures = frozen ? null : figures(bill, false);

        List<BillLineDto> lines = bill.getBillingModel() == BillingModel.RUNNING_ACCOUNT
                ? lineDtos(bill, orgId) : List.of();

        List<BillAdjustmentDto> adjustments = new ArrayList<>();
        if (frozen) {
            for (ContractBillAdjustment a : bill.getAdjustments()) {
                adjustments.add(new BillAdjustmentDto(a.getId(), a.getRuleId(), a.getKind(), a.getLabel(), a.getEffect(),
                        a.getBasis(), a.getRate(), a.getAmount(), a.getSource(), false));
            }
        } else {
            for (BillMath.Adjustment a : figures.ruleLines()) {
                adjustments.add(new BillAdjustmentDto(null, a.ruleId(), a.kind(), a.label(), a.effect(), a.basis(),
                        a.rate(), a.amount(), a.source(), true));
            }
            for (ContractBillAdjustment a : bill.getAdjustments()) {
                adjustments.add(new BillAdjustmentDto(a.getId(), a.getRuleId(), a.getKind(), a.getLabel(), a.getEffect(),
                        a.getBasis(), a.getRate(), a.getAmount(), a.getSource(), false));
            }
        }

        BigDecimal gross = frozen ? bill.getGrossCertified() : figures.totals().gross();
        BigDecimal additions = frozen ? bill.getAdditionsTotal() : figures.totals().additions();
        BigDecimal deductions = frozen ? bill.getDeductionsTotal() : figures.totals().deductions();
        BigDecimal net = frozen ? bill.getNetPayable() : figures.totals().net();
        BigDecimal previous = frozen ? bill.getPreviousCertified() : figures.previous();

        String milestoneName = null;
        LocalDate milestoneTarget = null;
        BigDecimal milestoneBefore = null;
        List<MilestoneRequirementDto> requirementDtos = List.of();
        if (bill.getContractMilestoneId() != null) {
            ContractMilestone milestone = contract.getMilestones().stream()
                    .filter(m -> m.getId().equals(bill.getContractMilestoneId())).findFirst().orElse(null);
            if (milestone != null) {
                milestoneName = milestone.getName();
                milestoneTarget = milestone.getTargetDate();
            }
            BigDecimal certifiedSoFar = certifiedPercent(orgId, bill.getSubContractId(), bill.getContractMilestoneId());
            if (frozen && bill.getCertifiedPercent() != null) {
                certifiedSoFar = certifiedSoFar.subtract(bill.getCertifiedPercent());
            }
            milestoneBefore = certifiedSoFar.setScale(2, RoundingMode.HALF_UP);
            requirementDtos = requirements.findForMilestones(orgId, List.of(bill.getContractMilestoneId())).stream()
                    .map(ContractBillingService::requirementDto).toList();
        }

        return new BillDto(bill.getId(), bill.getProjectId(), contract.getProjectName(), contract.getId(),
                trimToNull(contract.getContractId()), contract.getContractName(), contract.getContractorName(),
                contract.getContractValue(), bill.getBillingModel(), bill.getBillNumber(), bill.getStatus(),
                bill.getPeriodFrom(), bill.getPeriodTo(), bill.getContractMilestoneId(), milestoneName, milestoneTarget,
                bill.getMilestoneValue(), milestoneBefore, bill.getClaimedPercent(), bill.getCertifiedPercent(),
                bill.getContractorReference(), bill.getLocation(), bill.getMeasurementDate(), bill.getMeasuredBy(),
                bill.getClientRepresentative(), bill.getRemarks(), bill.getReturnReason(), lines, requirementDtos,
                adjustments, ContractBillingService.grossClaimed(bill), gross, additions, deductions, net, previous,
                previous.add(gross), frozen, support.nameOf(bill.getPreparedBy(), orgId),
                support.nameOf(bill.getSubmittedBy(), orgId), bill.getSubmittedAt(),
                support.nameOf(bill.getVerifiedBy(), orgId), bill.getVerifiedAt(),
                support.nameOf(bill.getCertifiedBy(), orgId), bill.getCertifiedAt(),
                support.nameOf(bill.getApprovedBy(), orgId), bill.getApprovedAt(), bill.isSelfApproved(),
                bill.getPayableId(), bill.getCreatedAt(), bill.getUpdatedAt());
    }

    private List<BillLineDto> lineDtos(ContractBill bill, Long orgId) {
        Map<UUID, BigDecimal> progress = new HashMap<>();
        if (bill.getStatus().isEditable()) {
            for (ContractBillLine line : bill.getLines()) {
                boqItems.findScoped(line.getBoqItemId(), orgId)
                        .map(ContractBoqItem::getWbsElementId)
                        .flatMap(elementId -> elements.findByIdAndOrganization_Id(elementId, orgId))
                        .map(WbsElement::getProgress)
                        .ifPresent(percent -> progress.put(line.getBoqItemId(), BigDecimal.valueOf(percent)));
            }
        }
        List<BillLineDto> dtos = new ArrayList<>();
        for (ContractBillLine line : bill.getLines()) {
            BigDecimal thisQuantity = line.getAcceptedQuantity() != null ? line.getAcceptedQuantity() : line.getClaimedQuantity();
            BigDecimal cumulative = line.getPreviousQuantity().add(thisQuantity);
            BigDecimal contractQuantity = line.getContractQuantity();
            BigDecimal percent = contractQuantity.signum() > 0
                    ? cumulative.multiply(HUNDRED).divide(contractQuantity, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(2);
            BigDecimal suggested = null;
            BigDecimal activityPercent = progress.get(line.getBoqItemId());
            if (activityPercent != null) {
                BigDecimal suggestedCumulative = BillMath.quantity(contractQuantity.multiply(activityPercent).divide(HUNDRED));
                suggested = suggestedCumulative.subtract(line.getPreviousQuantity()).max(BillMath.quantity(BigDecimal.ZERO));
            }
            dtos.add(new BillLineDto(line.getId(), line.getBoqItemId(), line.getItemCode(), line.getDescription(),
                    line.getUnit(), contractQuantity, line.getRate(), line.getPreviousQuantity(), line.getClaimedQuantity(),
                    line.getMeasuredQuantity(), line.getAcceptedQuantity(), BillMath.quantity(cumulative),
                    BillMath.quantity(contractQuantity.subtract(cumulative)), percent,
                    BillMath.lineAmount(thisQuantity, line.getRate()), BillMath.lineAmount(cumulative, line.getRate()),
                    lineStatus(line), suggested, line.getRemarks()));
        }
        return dtos;
    }

    static BillLineStatus lineStatus(ContractBillLine line) {
        if (line.getClaimedQuantity().signum() == 0) {
            return BillLineStatus.NOT_CLAIMED;
        }
        if (line.getAcceptedQuantity() == null) {
            return BillLineStatus.UNDER_REVIEW;
        }
        if (line.getAcceptedQuantity().signum() == 0) {
            return BillLineStatus.REJECTED;
        }
        return line.getAcceptedQuantity().compareTo(line.getClaimedQuantity()) < 0
                ? BillLineStatus.PART_ACCEPTED : BillLineStatus.VERIFIED;
    }
}
