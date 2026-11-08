package org.tornotron.echno_backend.payable;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.payable.mapper.PayableMapper;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNote;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNoteRepository;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.payable.dto.PayableCreationDto;
import org.tornotron.echno_backend.payable.dto.PayableDto;
import org.tornotron.echno_backend.payable.enums.ContractType;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserRepository;
import org.tornotron.echno_backend.vendor.Vendor;
import org.tornotron.echno_backend.vendor.VendorRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Create, read, and payment-recording operations for accounts payable.
 *
 * <p>On create it validates the referenced employee and project (both required) and the
 * optional vendor and goods-received note, all within the current organization, and
 * defaults the paid amount to zero. The payable number is unique within the organization,
 * not across the estate: it is the tenant's own reference series, so two tenants may
 * legitimately use the same one. An opening paid amount is held to the same ceiling
 * recordPayment enforces. Recording a payment takes a pessimistic lock on the
 * payable so concurrent payments serialize their read-modify-write on the paid amount, and
 * rejects a payment that would push the paid total past the recorded amount. A reversal takes
 * the same lock.
 *
 * <p>Each read and write from the payables endpoints asks the access matrix about the payable itself
 * ({@link PayableAccess}): every list is narrowed to what the caller may read, one they may not read is
 * reported missing, a new payable is checked on where it is recorded and a payment on the payable it
 * is recorded against. Two paths from contract billing are not asked, since each runs under that
 * workflow's own guard: {@link #raiseForApprovedBill}, which raises the payable a bill's final approval
 * hands to finance, and {@link #reversePayment}, which takes back a bill payment the bank returned.
 */
@Service
public class PayableService {

    private final PayableRepository payableRepository;
    private final VendorRepository vendorRepository;
    private final GoodsReceivedNoteRepository goodsReceivedNoteRepository;
    private final UserRepository userRepository;
    private final PayableMapper payableMapper;
    private final TenantEntityHelper tenantEntityHelper;
    private final EmployeeRepository employeeRepository;
    private final ProjectRepository projectRepository;
    private final PayableAccess payableAccess;

    public PayableService(PayableRepository payableRepository,
                          VendorRepository vendorRepository,
                          GoodsReceivedNoteRepository goodsReceivedNoteRepository,
                          UserRepository userRepository,
                          PayableMapper payableMapper,
                          TenantEntityHelper tenantEntityHelper,
                          EmployeeRepository employeeRepository,
                          ProjectRepository projectRepository,
                          PayableAccess payableAccess) {
        this.payableRepository = payableRepository;
        this.vendorRepository = vendorRepository;
        this.goodsReceivedNoteRepository = goodsReceivedNoteRepository;
        this.userRepository = userRepository;
        this.payableMapper = payableMapper;
        this.tenantEntityHelper = tenantEntityHelper;
        this.employeeRepository = employeeRepository;
        this.projectRepository = projectRepository;
        this.payableAccess = payableAccess;
    }

    /**
     * Creates a payable after validating its related entities within the current organization, if the
     * caller may record one with its project, creator and vendor.
     *
     * @param creationDto The payable details, including the creating employee, project, and optional vendor and GRN.
     * @return The created payable DTO.
     * @throws InvalidRequestException if the recorded amount is not positive, or the opening paid amount is negative or exceeds it.
     * @throws DuplicateResourceException if a payable with the same number already exists in the organization.
     * @throws ResourceNotFoundException if the employee, project, vendor, or goods-received note cannot be found in the organization.
     * @throws org.springframework.security.access.AccessDeniedException if the caller may not record a payable there.
     */
    @Transactional
    public PayableDto createPayable(PayableCreationDto creationDto) {
        Payable payable = build(creationDto);
        // Checked on the payable as recorded, so nobody records one where they may not.
        payableAccess.require(AccessPermission.CREATE, PayableAccess.facts(payable));
        return payableMapper.toDto(payableRepository.save(payable));
    }

    /**
     * Raises the payable a contract bill's final approval hands to finance. The approval's own guard
     * has already decided the caller may do this, so the payables area is not asked: an approver who
     * does not handle payables still hands the bill on. Validates as {@link #createPayable} does.
     *
     * @param creationDto The payable details, including the approving employee and the bill's project.
     * @return The created payable DTO.
     */
    @Transactional
    public PayableDto raiseForApprovedBill(PayableCreationDto creationDto) {
        return payableMapper.toDto(payableRepository.save(build(creationDto)));
    }

    /** A new payable from the details, validated and linked within the current organization, not yet saved. */
    private Payable build(PayableCreationDto creationDto) {
        BigDecimal amountRecorded = creationDto.getAmountRecorded();
        if (amountRecorded == null || amountRecorded.signum() <= 0) {
            throw new InvalidRequestException("Amount recorded must be greater than zero");
        }

        // The opening paid amount is held to the same rule recordPayment enforces on every
        // later payment. Without it a payable can be created already past its recorded
        // amount, and since no payment may push the paid total any higher and the module has
        // no update or delete endpoint, that negative balance would stand for good.
        BigDecimal openingPaid = creationDto.getAmountPaid() != null ? creationDto.getAmountPaid() : BigDecimal.ZERO;
        if (openingPaid.signum() < 0) {
            throw new InvalidRequestException("Amount paid cannot be negative");
        }
        if (openingPaid.compareTo(amountRecorded) > 0) {
            throw new InvalidRequestException(
                    "Opening amount paid of " + openingPaid + " exceeds the recorded amount of " + amountRecorded);
        }

        // Check for duplicate payable number
        if (payableRepository.existsByPayableNumberAndOrganization_Id(creationDto.getPayableNumber(),TenantContext.getCurrentOrgId())) {
            throw new DuplicateResourceException("Payable with number " + creationDto.getPayableNumber() + " already exists");
        }


        Employee createdBy = employeeRepository.findByIdAndOrganizationId(creationDto.getCreatedBy(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee with ID " + creationDto.getCreatedBy() + " was not found in this organization"));

        // Validate project
        Project project = projectRepository.findByIdAndOrganization_Id(creationDto.getProjectId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Project with ID " + creationDto.getProjectId() + " was not found in this organization"));

        // Validate vendor if provided
        Vendor vendor = null;
        if (creationDto.getVendorId() != null) {
            vendor = vendorRepository.findByIdAndOrganization_Id(creationDto.getVendorId(),TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Vendor with ID " + creationDto.getVendorId() + " was not found in this organization"));
        }

        // Validate GRN if provided
        GoodsReceivedNote grn = null;
        if (creationDto.getGoodsReceivedNoteId() != null) {
            grn = goodsReceivedNoteRepository.findByIdAndOrganization_Id(creationDto.getGoodsReceivedNoteId(),TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Goods received note with ID " + creationDto.getGoodsReceivedNoteId() + " was not found in this organization"));
        }

        Payable payable = new Payable();
        payable.setPayableNumber(creationDto.getPayableNumber());
        payable.setContractorName(creationDto.getContractorName());
        payable.setContractType(ContractType.valueOf(creationDto.getContractType()));
        payable.setAmountRecorded(amountRecorded);
        payable.setAmountPaid(openingPaid);
        payable.setVendor(vendor);
        payable.setGoodsReceivedNote(grn);
        payable.setCreatedBy(createdBy);
        payable.setProject(project);
        payable.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        return payable;
    }

    /**
     * Records a payment against a payable, increasing its paid amount under a pessimistic lock.
     *
     * @param id The ID of the payable to pay against.
     * @param paymentAmount The amount to add to the paid total; must be greater than zero.
     * @return The updated payable DTO.
     * @throws InvalidRequestException if the payment amount is null or not positive, or if it would overpay the recorded amount.
     * @throws ResourceNotFoundException if no payable with the given ID exists in the organization, or the caller may not read it.
     * @throws org.springframework.security.access.AccessDeniedException if the caller may read the payable but not record a payment on it.
     */
    @Transactional
    public PayableDto recordPayment(Long id, BigDecimal paymentAmount) {
        if (paymentAmount == null || paymentAmount.signum() <= 0) {
            throw new InvalidRequestException("Payment amount must be greater than zero");
        }

        // Pessimistic lock so concurrent payments against the same payable
        // serialize their read-modify-write on amountPaid (no lost updates).
        Payable payable = payableRepository.lockByIdAndOrganizationId(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> notFound(id));
        // A payment changes only the amount paid, so the payable's facts are the same after it.
        payableAccess.check(AccessPermission.EDIT, PayableAccess.facts(payable), () -> notFound(id));

        BigDecimal currentPaid = payable.getAmountPaid() != null ? payable.getAmountPaid() : BigDecimal.ZERO;
        BigDecimal newPaid = currentPaid.add(paymentAmount);

        BigDecimal total = payable.getAmountRecorded();
        if (total != null && newPaid.compareTo(total) > 0) {
            throw new InvalidRequestException(
                    "Payment of " + paymentAmount + " would overpay payable " + id +
                    " (recorded " + total + ", already paid " + currentPaid + ")");
        }

        payable.setAmountPaid(newPaid);

        payable = payableRepository.save(payable);
        return payableMapper.toDto(payable);
    }

    /**
     * Takes back a payment recorded earlier, because the bank returned it. Holds the same pessimistic
     * lock as {@link #recordPayment}, so the two serialize on the paid amount. Called only by contract
     * billing, under its own guard, so the payables area is not asked.
     *
     * @param id The ID of the payable.
     * @param amount The amount to take off the paid total; must be greater than zero.
     * @return The updated payable DTO.
     * @throws InvalidRequestException if the amount is null or not positive, or more than has been paid.
     * @throws ResourceNotFoundException if no payable with the given ID exists in the organization.
     */
    @Transactional
    public PayableDto reversePayment(Long id, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidRequestException("Reversal amount must be greater than zero");
        }
        Payable payable = payableRepository.lockByIdAndOrganizationId(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Payable with ID " + id + " was not found in this organization"));
        BigDecimal currentPaid = payable.getAmountPaid() != null ? payable.getAmountPaid() : BigDecimal.ZERO;
        if (amount.compareTo(currentPaid) > 0) {
            throw new InvalidRequestException(
                    "Reversal of " + amount + " is more than the " + currentPaid + " paid on payable " + id);
        }
        payable.setAmountPaid(currentPaid.subtract(amount));
        return payableMapper.toDto(payableRepository.save(payable));
    }

    /**
     * Retrieves a single payable by its ID within the current organization.
     *
     * @param id The ID of the payable to retrieve.
     * @return The payable DTO.
     * @throws ResourceNotFoundException if no payable with the given ID exists in the organization, or the caller may not read it.
     */
    @Transactional(readOnly = true)
    public PayableDto getPayableById(Long id) {
        Payable payable = payableRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> notFound(id));
        payableAccess.check(AccessPermission.READ, PayableAccess.facts(payable), () -> notFound(id));
        return payableMapper.toDto(payable);
    }

    /**
     * Returns a page of the payables the caller may read, newest first.
     *
     * @param pageNo The zero-based page index.
     * @param pageSize The number of payables per page.
     * @return A page of payable DTOs sorted descending by creation time.
     */
    @Transactional(readOnly = true)
    public Page<PayableDto> getAllPayables(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        AccessFilter visible = payableAccess.filter(AccessPermission.READ);
        if (visible.isEmpty()) {
            return Page.empty(pageable);
        }
        Page<Payable> payables = visible.everything()
                ? payableRepository.findAll(pageable)
                : payableRepository.findAll(PayableAccess.admittedBy(visible), pageable);
        return payables.map(payable -> payableMapper.toDto(payable));
    }

    /**
     * Returns the payables raised against a given vendor within the current organization that the caller may read.
     *
     * @param vendorId The ID of the vendor whose payables to list.
     * @return The list of payable DTOs for that vendor.
     */
    @Transactional(readOnly = true)
    public List<PayableDto> getPayablesByVendor(Long vendorId) {
        AccessFilter visible = payableAccess.filter(AccessPermission.READ);
        if (visible.isEmpty()) {
            return List.of();
        }
        List<Payable> payables = visible.everything()
                ? payableRepository.findByVendorIdAndOrganization_id(vendorId, TenantContext.getCurrentOrgId())
                : payableRepository.findAll(PayableAccess.admittedBy(visible).and(PayableRepository.owedTo(vendorId)));
        return payables.stream()
                .map(payable -> payableMapper.toDto(payable))
                .collect(Collectors.toList());
    }

    /**
     * One page of the same rows the caller may read, newest first, with the id as tie-break.
     *
     * @param pageNo   Zero-based page index.
     * @param pageSize Rows per page.
     * @return The requested page.
     */
    @Transactional(readOnly = true)
    public Page<PayableDto> getPayablesByVendor(Long vendorId, int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        AccessFilter visible = payableAccess.filter(AccessPermission.READ);
        if (visible.isEmpty()) {
            return Page.empty(pageable);
        }
        Page<Payable> payables = visible.everything()
                ? payableRepository.findByVendorIdAndOrganization_id(vendorId, TenantContext.getCurrentOrgId(), pageable)
                : payableRepository.findAll(
                        PayableAccess.admittedBy(visible).and(PayableRepository.owedTo(vendorId)), pageable);
        return payables.map(payable -> payableMapper.toDto(payable));
    }

    /**
     * Returns the payables the caller may read that still have an unpaid balance.
     *
     * @return The list of outstanding payable DTOs.
     */
    @Transactional(readOnly = true)
    public List<PayableDto> getOutstandingPayables() {
        AccessFilter visible = payableAccess.filter(AccessPermission.READ);
        if (visible.isEmpty()) {
            return List.of();
        }
        List<Payable> payables = visible.everything()
                ? payableRepository.findOutstandingPayables()
                : payableRepository.findAll(PayableAccess.admittedBy(visible).and(PayableRepository.outstanding()));
        return payables.stream()
                .map(payable -> payableMapper.toDto(payable))
                .collect(Collectors.toList());
    }

    /**
     * One page of the same rows the caller may read, newest first, with the id as tie-break.
     *
     * @param pageNo   Zero-based page index.
     * @param pageSize Rows per page.
     * @return The requested page.
     */
    @Transactional(readOnly = true)
    public Page<PayableDto> getOutstandingPayables(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        AccessFilter visible = payableAccess.filter(AccessPermission.READ);
        if (visible.isEmpty()) {
            return Page.empty(pageable);
        }
        Page<Payable> payables = visible.everything()
                ? payableRepository.findOutstandingPayables(pageable)
                : payableRepository.findAll(PayableAccess.admittedBy(visible).and(PayableRepository.outstanding()), pageable);
        return payables.map(payable -> payableMapper.toDto(payable));
    }

    private static ResourceNotFoundException notFound(Long id) {
        return new ResourceNotFoundException("Payable with ID " + id + " was not found in this organization");
    }
}
