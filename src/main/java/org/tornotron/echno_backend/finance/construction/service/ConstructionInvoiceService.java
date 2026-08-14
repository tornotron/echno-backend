package org.tornotron.echno_backend.finance.construction.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.configuration.MoneyUtils;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceStatus;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentStatus;
import org.tornotron.echno_backend.finance.construction.domain.ConstructionInvoice;
import org.tornotron.echno_backend.finance.construction.domain.ConstructionInvoiceLine;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionInvoiceDto;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionInvoiceLineRequest;
import org.tornotron.echno_backend.finance.construction.dtos.CreateConstructionInvoiceRequest;
import org.tornotron.echno_backend.finance.construction.dtos.UpdateConstructionInvoiceRequest;
import org.tornotron.echno_backend.finance.construction.mapper.ConstructionInvoiceMapper;
import org.tornotron.echno_backend.finance.construction.repositories.ConstructionInvoiceRepository;
import org.tornotron.echno_backend.finance.construction.repositories.ConstructionInvoiceSpecifications;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * CRUD + list for construction invoices. This increment deliberately does NO ledger
 * or journal posting: money totals are computed arithmetically from the line items
 * and status is set directly. A later increment will add the ledger-posting hooks
 * (create the receivable/payable journal entry on issue, reverse it on cancel) that
 * the AR invoice module already has.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConstructionInvoiceService {

    private static final String DOC_TYPE = "CINV";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ConstructionInvoiceRepository invoiceRepo;
    private final EntryNumberGenerator numberGen;
    private final ConstructionInvoiceMapper mapper;
    private final TenantEntityHelper tenantEntityHelper;

    @Transactional(readOnly = true)
    public ConstructionInvoiceDto findById(UUID id) {
        return mapper.toDto(invoiceRepo.findByIdWithLines(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Construction invoice with ID " + id + " was not found")));
    }

    @Transactional(readOnly = true)
    public Page<ConstructionInvoiceDto> findAll(Long projectId,
                                                Long vendorId,
                                                ConstructionInvoiceStatus status,
                                                ConstructionInvoiceType type,
                                                Pageable pageable) {
        return invoiceRepo.findAll(
                        ConstructionInvoiceSpecifications.withFilters(projectId, vendorId, status, type),
                        pageable)
                .map(mapper::toDto);
    }

    @Transactional
    public ConstructionInvoiceDto create(CreateConstructionInvoiceRequest req) {
        if (req.dueDate().isBefore(req.issueDate())) {
            throw new InvalidRequestException("Due date cannot be before issue date");
        }

        ConstructionInvoice inv = new ConstructionInvoice();
        inv.setInvoiceNumber(numberGen.next(DOC_TYPE));
        inv.setType(req.type());
        inv.setStatus(ConstructionInvoiceStatus.DRAFT);
        inv.setPaymentStatus(ConstructionPaymentStatus.UNPAID);
        inv.setProjectId(req.projectId());
        inv.setVendorId(req.vendorId());
        inv.setPurchaseOrderId(req.purchaseOrderId());
        inv.setGoodsReceiptId(req.goodsReceiptId());
        inv.setIssueDate(req.issueDate());
        inv.setDueDate(req.dueDate());
        inv.setPaymentTerms(req.paymentTerms());
        inv.setPaymentMethod(req.paymentMethod());
        inv.setGstNumber(req.gstNumber());
        inv.setTaxType(req.taxType());
        inv.setNotes(req.notes());
        inv.setTermsAndConditions(req.termsAndConditions());
        inv.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

        applyLinesAndTotals(inv, req.lines());

        ConstructionInvoice saved = invoiceRepo.save(inv);
        log.info("Created construction invoice {}", saved.getInvoiceNumber());
        return mapper.toDto(saved);
    }

    @Transactional
    public ConstructionInvoiceDto update(UUID id, UpdateConstructionInvoiceRequest req) {
        if (req.dueDate().isBefore(req.issueDate())) {
            throw new InvalidRequestException("Due date cannot be before issue date");
        }

        ConstructionInvoice inv = invoiceRepo.findByIdWithLines(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Construction invoice with ID " + id + " was not found"));

        if (inv.getStatus() == ConstructionInvoiceStatus.CANCELLED) {
            throw new InvalidRequestException(
                    "Construction invoice " + inv.getInvoiceNumber() + " is cancelled and cannot be updated");
        }

        inv.setType(req.type());
        inv.setStatus(req.status());
        inv.setPaymentStatus(req.paymentStatus());
        inv.setProjectId(req.projectId());
        inv.setVendorId(req.vendorId());
        inv.setPurchaseOrderId(req.purchaseOrderId());
        inv.setGoodsReceiptId(req.goodsReceiptId());
        inv.setIssueDate(req.issueDate());
        inv.setDueDate(req.dueDate());
        inv.setPaymentDate(req.paymentDate());
        inv.setPaymentTerms(req.paymentTerms());
        inv.setPaymentMethod(req.paymentMethod());
        inv.setGstNumber(req.gstNumber());
        inv.setTaxType(req.taxType());
        inv.setNotes(req.notes());
        inv.setTermsAndConditions(req.termsAndConditions());

        inv.getLines().clear();
        applyLinesAndTotals(inv, req.lines());

        ConstructionInvoice saved = invoiceRepo.saveAndFlush(inv);
        log.info("Updated construction invoice {}", saved.getInvoiceNumber());
        return mapper.toDto(saved);
    }

    /**
     * Rebuilds the invoice lines from the request and recomputes every money total.
     * Per line: subtotal = quantity * unitPrice; discount = subtotal * discountRate%;
     * tax = (subtotal - discount) * taxRate% (tax charged on the net-of-discount base);
     * total = subtotal + tax - discount. The invoice header sums the line values;
     * balance = total - paid (paid is always zero in this increment).
     */
    private void applyLinesAndTotals(ConstructionInvoice inv, List<ConstructionInvoiceLineRequest> lineReqs) {
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;
        BigDecimal discountTotal = BigDecimal.ZERO;

        for (ConstructionInvoiceLineRequest lr : lineReqs) {
            BigDecimal qty = MoneyUtils.normalize(lr.quantity());
            BigDecimal price = MoneyUtils.normalize(lr.unitPrice());
            BigDecimal lineSub = MoneyUtils.normalize(qty.multiply(price));

            BigDecimal discRate = MoneyUtils.normalize(lr.discountRate());
            BigDecimal discAmt = MoneyUtils.normalize(lineSub.multiply(discRate).divide(HUNDRED));

            BigDecimal taxRate = MoneyUtils.normalize(lr.taxRate());
            BigDecimal taxableBase = MoneyUtils.normalize(lineSub.subtract(discAmt));
            BigDecimal taxAmt = MoneyUtils.normalize(taxableBase.multiply(taxRate).divide(HUNDRED));

            BigDecimal lineTotal = MoneyUtils.normalize(lineSub.add(taxAmt).subtract(discAmt));

            ConstructionInvoiceLine line = new ConstructionInvoiceLine();
            line.setDescription(lr.description());
            line.setQuantity(qty);
            line.setUnit(lr.unit());
            line.setUnitPrice(price);
            line.setTaxRate(taxRate);
            line.setTaxAmount(taxAmt);
            line.setDiscountRate(discRate);
            line.setDiscountAmount(discAmt);
            line.setSubtotal(lineSub);
            line.setTotal(lineTotal);
            line.setInventoryItemId(lr.inventoryItemId());
            line.setAssetId(lr.assetId());
            line.setTaskId(lr.taskId());
            inv.addLine(line);

            subtotal = subtotal.add(lineSub);
            taxTotal = taxTotal.add(taxAmt);
            discountTotal = discountTotal.add(discAmt);
        }

        BigDecimal total = MoneyUtils.normalize(subtotal.add(taxTotal).subtract(discountTotal));
        BigDecimal paid = MoneyUtils.normalize(inv.getPaidAmount());

        inv.setSubtotal(MoneyUtils.normalize(subtotal));
        inv.setTaxAmount(MoneyUtils.normalize(taxTotal));
        inv.setDiscountAmount(MoneyUtils.normalize(discountTotal));
        inv.setTotalAmount(total);
        inv.setPaidAmount(paid);
        inv.setBalanceAmount(MoneyUtils.normalize(total.subtract(paid)));
    }
}
