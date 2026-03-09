package org.tornotron.echno_backend.payable;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.PayableDtoConvertor;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNote;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNoteRepository;
import org.tornotron.echno_backend.payable.dto.PayableCreationDto;
import org.tornotron.echno_backend.payable.dto.PayableDto;
import org.tornotron.echno_backend.payable.enums.ContractType;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserRepository;
import org.tornotron.echno_backend.vendor.Vendor;
import org.tornotron.echno_backend.vendor.VendorRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PayableService {

    private final PayableRepository payableRepository;
    private final VendorRepository vendorRepository;
    private final GoodsReceivedNoteRepository goodsReceivedNoteRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final TenantEntityHelper tenantEntityHelper;
    private final EmployeeRepository employeeRepository;

    public PayableService(PayableRepository payableRepository,
                          VendorRepository vendorRepository,
                          GoodsReceivedNoteRepository goodsReceivedNoteRepository,
                          UserRepository userRepository,
                          FileStorageService fileStorageService,
                          TenantEntityHelper tenantEntityHelper, EmployeeRepository employeeRepository) {
        this.payableRepository = payableRepository;
        this.vendorRepository = vendorRepository;
        this.goodsReceivedNoteRepository = goodsReceivedNoteRepository;
        this.userRepository = userRepository;
        this.fileStorageService = fileStorageService;
        this.tenantEntityHelper = tenantEntityHelper;
        this.employeeRepository = employeeRepository;
    }

    @Transactional
    public PayableDto createPayable(PayableCreationDto creationDto) {
        // Check for duplicate payable number
        if (payableRepository.existsByPayableNumber(creationDto.getPayableNumber())) {
            throw new DuplicateResourceException("Payable with number " + creationDto.getPayableNumber() + " already exists");
        }


        Employee createdBy = employeeRepository.findByIdAndOrganizationId(creationDto.getCreatedBy(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: "+ creationDto.getCreatedBy()));

        // Validate vendor if provided
        Vendor vendor = null;
        if (creationDto.getVendorId() != null) {
            vendor = vendorRepository.findById(creationDto.getVendorId())
                    .orElseThrow(() -> new ResourceNotFoundException("Vendor not found with id: " + creationDto.getVendorId()));
        }

        // Validate GRN if provided
        GoodsReceivedNote grn = null;
        if (creationDto.getGoodsReceivedNoteId() != null) {
            grn = goodsReceivedNoteRepository.findById(creationDto.getGoodsReceivedNoteId())
                    .orElseThrow(() -> new ResourceNotFoundException("GRN not found with id: " + creationDto.getGoodsReceivedNoteId()));
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
        payable.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

        payable = payableRepository.save(payable);
        return PayableDtoConvertor.convertToDto(payable, fileStorageService);
    }

    @Transactional
    public PayableDto recordPayment(Long id, BigDecimal paymentAmount) {
        Payable payable = payableRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payable not found with id: " + id));

        BigDecimal currentPaid = payable.getAmountPaid() != null ? payable.getAmountPaid() : BigDecimal.ZERO;
        payable.setAmountPaid(currentPaid.add(paymentAmount));

        payable = payableRepository.save(payable);
        return PayableDtoConvertor.convertToDto(payable, fileStorageService);
    }

    @Transactional(readOnly = true)
    public PayableDto getPayableById(Long id) {
        Payable payable = payableRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payable not found with id: " + id));
        return PayableDtoConvertor.convertToDto(payable, fileStorageService);
    }

    @Transactional(readOnly = true)
    public List<PayableDto> getAllPayables() {
        return payableRepository.findAll().stream()
                .map(payable -> PayableDtoConvertor.convertToDto(payable, fileStorageService))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<PayableDto> getAllPayables(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return payableRepository.findAll(pageable)
                .map(payable -> PayableDtoConvertor.convertToDto(payable, fileStorageService));
    }

    @Transactional(readOnly = true)
    public List<PayableDto> getPayablesByVendor(Long vendorId) {
        return payableRepository.findByVendorId(vendorId).stream()
                .map(payable -> PayableDtoConvertor.convertToDto(payable, fileStorageService))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<PayableDto> getOutstandingPayables() {
        return payableRepository.findOutstandingPayables().stream()
                .map(payable -> PayableDtoConvertor.convertToDto(payable, fileStorageService))
                .collect(Collectors.toList());
    }
}
