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
 * defaults the paid amount to zero. Recording a payment takes a pessimistic lock on the
 * payable so concurrent payments serialize their read-modify-write on the paid amount, and
 * rejects a payment that would push the paid total past the recorded amount.
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

    public PayableService(PayableRepository payableRepository,
                          VendorRepository vendorRepository,
                          GoodsReceivedNoteRepository goodsReceivedNoteRepository,
                          UserRepository userRepository,
                          PayableMapper payableMapper,
                          TenantEntityHelper tenantEntityHelper,
                          EmployeeRepository employeeRepository,
                          ProjectRepository projectRepository) {
        this.payableRepository = payableRepository;
        this.vendorRepository = vendorRepository;
        this.goodsReceivedNoteRepository = goodsReceivedNoteRepository;
        this.userRepository = userRepository;
        this.payableMapper = payableMapper;
        this.tenantEntityHelper = tenantEntityHelper;
        this.employeeRepository = employeeRepository;
        this.projectRepository = projectRepository;
    }

    /**
     * Creates a payable after validating its related entities within the current organization.
     *
     * @param creationDto The payable details, including the creating employee, project, and optional vendor and GRN.
     * @return The created payable DTO.
     * @throws DuplicateResourceException if a payable with the same number already exists in the organization.
     * @throws ResourceNotFoundException if the employee, project, vendor, or goods-received note cannot be found in the organization.
     */
    @Transactional
    public PayableDto createPayable(PayableCreationDto creationDto) {
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
        payable.setAmountRecorded(creationDto.getAmountRecorded());
        payable.setAmountPaid(creationDto.getAmountPaid() != null ? creationDto.getAmountPaid() : BigDecimal.ZERO);
        payable.setVendor(vendor);
        payable.setGoodsReceivedNote(grn);
        payable.setCreatedBy(createdBy);
        payable.setProject(project);
        payable.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

        payable = payableRepository.save(payable);
        return payableMapper.toDto(payable);
    }

    /**
     * Records a payment against a payable, increasing its paid amount under a pessimistic lock.
     *
     * @param id The ID of the payable to pay against.
     * @param paymentAmount The amount to add to the paid total; must be greater than zero.
     * @return The updated payable DTO.
     * @throws InvalidRequestException if the payment amount is null or not positive, or if it would overpay the recorded amount.
     * @throws ResourceNotFoundException if no payable with the given ID exists in the organization.
     */
    @Transactional
    public PayableDto recordPayment(Long id, BigDecimal paymentAmount) {
        if (paymentAmount == null || paymentAmount.signum() <= 0) {
            throw new InvalidRequestException("Payment amount must be greater than zero");
        }

        // Pessimistic lock so concurrent payments against the same payable
        // serialize their read-modify-write on amountPaid (no lost updates).
        Payable payable = payableRepository.lockByIdAndOrganizationId(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Payable with ID " + id + " was not found in this organization"));

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
     * Retrieves a single payable by its ID within the current organization.
     *
     * @param id The ID of the payable to retrieve.
     * @return The payable DTO.
     * @throws ResourceNotFoundException if no payable with the given ID exists in the organization.
     */
    @Transactional(readOnly = true)
    public PayableDto getPayableById(Long id) {
        Payable payable = payableRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Payable with ID " + id + " was not found in this organization"));
        return payableMapper.toDto(payable);
    }

    /**
     * Returns every payable visible to the current organization.
     *
     * @return The list of payable DTOs.
     */
    @Transactional(readOnly = true)
    public List<PayableDto> getAllPayables() {
        return payableRepository.findAll().stream()
                .map(payable -> payableMapper.toDto(payable))
                .collect(Collectors.toList());
    }

    /**
     * Returns a page of payables, newest first.
     *
     * @param pageNo The zero-based page index.
     * @param pageSize The number of payables per page.
     * @return A page of payable DTOs sorted descending by creation time.
     */
    @Transactional(readOnly = true)
    public Page<PayableDto> getAllPayables(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return payableRepository.findAll(pageable)
                .map(payable -> payableMapper.toDto(payable));
    }

    /**
     * Returns the payables raised against a given vendor within the current organization.
     *
     * @param vendorId The ID of the vendor whose payables to list.
     * @return The list of payable DTOs for that vendor.
     */
    @Transactional(readOnly = true)
    public List<PayableDto> getPayablesByVendor(Long vendorId) {
        return payableRepository.findByVendorIdAndOrganization_id(vendorId,TenantContext.getCurrentOrgId()).stream()
                .map(payable -> payableMapper.toDto(payable))
                .collect(Collectors.toList());
    }

    /**
     * Returns the payables that still have an unpaid balance.
     *
     * @return The list of outstanding payable DTOs.
     */
    @Transactional(readOnly = true)
    public List<PayableDto> getOutstandingPayables() {
        return payableRepository.findOutstandingPayables().stream()
                .map(payable -> payableMapper.toDto(payable))
                .collect(Collectors.toList());
    }
}
