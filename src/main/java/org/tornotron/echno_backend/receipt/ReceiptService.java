package org.tornotron.echno_backend.receipt;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.expense.ExpenseAccess;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.receipt.dto.ReceiptCreationDto;
import org.tornotron.echno_backend.receipt.dto.ReceiptDto;
import org.tornotron.echno_backend.receipt.dto.ReceiptUpdateDto;
import org.tornotron.echno_backend.receipt.mapper.ReceiptMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * CRUD + list for receipts. The receipt is a flat header scoped to the current tenant;
 * its number is generated on create and never changes afterwards.
 *
 * <p>Receipts sit in the expenses area of the access matrix. Each read and write asks it about the
 * receipt itself ({@link ReceiptAccess} for its facts, {@link ExpenseAccess} for the answer): the list
 * is narrowed to what the caller may read, one they may not read is reported missing, and a write is
 * checked on the receipt as it stands and on where it leaves it.
 */
@Service
public class ReceiptService {

    private static final String DOC_TYPE = "RCP";

    private final ReceiptRepository receiptRepository;
    private final ReceiptMapper receiptMapper;
    private final TenantEntityHelper tenantEntityHelper;
    private final EntryNumberGenerator numberGenerator;
    private final ExpenseAccess access;

    public ReceiptService(ReceiptRepository receiptRepository,
                          ReceiptMapper receiptMapper,
                          TenantEntityHelper tenantEntityHelper,
                          EntryNumberGenerator numberGenerator,
                          ExpenseAccess access) {
        this.receiptRepository = receiptRepository;
        this.receiptMapper = receiptMapper;
        this.tenantEntityHelper = tenantEntityHelper;
        this.numberGenerator = numberGenerator;
        this.access = access;
    }

    @Transactional
    public ReceiptDto create(ReceiptCreationDto creationDto) {
        Organization organization = tenantEntityHelper.resolveCurrentOrganization();
        Receipt receipt = new Receipt();
        receipt.setOrganization(organization);
        receipt.setReceiptNumber(numberGenerator.next(DOC_TYPE));
        applyFields(receipt, creationDto.getType(), creationDto.getStatus(), creationDto.getAmount(),
                creationDto.getCurrency(), creationDto.getReceiptDate(), creationDto.getPaymentMethod(),
                creationDto.getTransactionId(), creationDto.getReferenceNumber(), creationDto.getReceivedFrom(),
                creationDto.getReceivedFromAddress(), creationDto.getTaxAmount(), creationDto.getTaxRate(),
                creationDto.getTaxType(), creationDto.getDescription(), creationDto.getNotes(),
                creationDto.getIssuedBy(), creationDto.getProjectId(), creationDto.getPaymentId(),
                creationDto.getInvoiceId(), creationDto.getCustomerId());
        // Checked on the receipt as recorded, so nobody records one where they may not.
        access.require(AccessPermission.CREATE, ReceiptAccess.facts(receipt));

        // saveAndFlush before mapping so the @CreationTimestamp and generated id are
        // populated on the returned DTO.
        Receipt saved = receiptRepository.saveAndFlush(receipt);
        return receiptMapper.toDto(saved);
    }

    @Transactional(readOnly = true)
    public ReceiptDto getById(Long id) {
        return receiptMapper.toDto(requireReceipt(id, AccessPermission.READ));
    }


    @Transactional(readOnly = true)
    public Page<ReceiptDto> getPaginated(int pageNo, int pageSize, String search, String status) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        AccessFilter visible = access.filter(AccessPermission.READ);
        if (visible.isEmpty()) {
            return Page.empty(pageable);
        }
        String pattern = searchPattern(search);
        String wanted = blankToNull(status);
        Page<Receipt> receipts = visible.everything()
                ? receiptRepository.search(pattern, wanted, pageable)
                : receiptRepository.findAll(
                        ReceiptAccess.admittedBy(visible).and(ReceiptRepository.matching(pattern, wanted)), pageable);
        return receipts.map(receiptMapper::toDto);
    }

    @Transactional
    public ReceiptDto update(Long id, ReceiptUpdateDto updateDto) {
        Receipt receipt = requireReceipt(id, AccessPermission.EDIT);

        applyFields(receipt, updateDto.getType(), updateDto.getStatus(), updateDto.getAmount(),
                updateDto.getCurrency(), updateDto.getReceiptDate(), updateDto.getPaymentMethod(),
                updateDto.getTransactionId(), updateDto.getReferenceNumber(), updateDto.getReceivedFrom(),
                updateDto.getReceivedFromAddress(), updateDto.getTaxAmount(), updateDto.getTaxRate(),
                updateDto.getTaxType(), updateDto.getDescription(), updateDto.getNotes(),
                updateDto.getIssuedBy(), updateDto.getProjectId(), updateDto.getPaymentId(),
                updateDto.getInvoiceId(), updateDto.getCustomerId());
        // An edit can move the receipt to another project, and must not move it out of what the
        // caller may edit.
        access.require(AccessPermission.EDIT, ReceiptAccess.facts(receipt));

        // saveAndFlush before mapping so the @UpdateTimestamp on the returned DTO reflects
        // this write.
        Receipt saved = receiptRepository.saveAndFlush(receipt);
        return receiptMapper.toDto(saved);
    }

    @Transactional
    public void delete(Long id) {
        receiptRepository.delete(requireReceipt(id, AccessPermission.DELETE));
    }

    /**
     * The receipt, if the caller may act on it this way. One they may not read is reported missing,
     * as one in another organization is; one they may read but not act on this way is refused.
     */
    private Receipt requireReceipt(Long id, String action) {
        Receipt receipt = receiptRepository
                .findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> notFound(id));
        access.check(action, ReceiptAccess.facts(receipt), () -> notFound(id));
        return receipt;
    }

    private static ResourceNotFoundException notFound(Long id) {
        return new ResourceNotFoundException("Receipt with ID " + id + " was not found in this organization");
    }

    /**
     * Copies the editable scalars onto the receipt. The receipt number, organization and
     * timestamps are managed elsewhere and never set from a request. Currency defaults to
     * the entity's INR when the request omits it.
     */
    private void applyFields(Receipt receipt, String type, String status, BigDecimal amount,
                             String currency, LocalDate receiptDate, String paymentMethod,
                             String transactionId, String referenceNumber, String receivedFrom,
                             String receivedFromAddress, BigDecimal taxAmount, BigDecimal taxRate,
                             String taxType, String description, String notes,
                             Long issuedBy, Long projectId, Long paymentId,
                             Long invoiceId, UUID customerId) {
        receipt.setType(type);
        receipt.setStatus(status);
        receipt.setAmount(amount);
        if (currency != null) {
            receipt.setCurrency(currency);
        }
        receipt.setReceiptDate(receiptDate);
        receipt.setPaymentMethod(paymentMethod);
        receipt.setTransactionId(transactionId);
        receipt.setReferenceNumber(referenceNumber);
        receipt.setReceivedFrom(receivedFrom);
        receipt.setReceivedFromAddress(receivedFromAddress);
        receipt.setTaxAmount(taxAmount);
        receipt.setTaxRate(taxRate);
        receipt.setTaxType(taxType);
        receipt.setDescription(description);
        receipt.setNotes(notes);
        receipt.setIssuedBy(issuedBy);
        receipt.setProjectId(projectId);
        receipt.setPaymentId(paymentId);
        receipt.setInvoiceId(invoiceId);
        receipt.setCustomerId(customerId);
    }

    /**
     * Builds a lower-cased {@code %...%} LIKE pattern for the search term, or null when
     * blank. The pattern is assembled here rather than with SQL {@code CONCAT} so no null
     * bind lands inside a {@code ||}, which CockroachDB mistypes as bytes.
     */
    private static String searchPattern(String value) {
        return (value == null || value.isBlank()) ? null : "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
