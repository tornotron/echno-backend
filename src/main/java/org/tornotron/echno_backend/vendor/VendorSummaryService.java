package org.tornotron.echno_backend.vendor;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.vendor.dto.VendorSummaryDto;

import java.math.BigDecimal;

@Service
public class VendorSummaryService {

    @PersistenceContext
    private EntityManager entityManager;

    private final VendorRepository vendorRepository;

    public VendorSummaryService(VendorRepository vendorRepository) {
        this.vendorRepository = vendorRepository;
    }

    @Transactional(readOnly = true)
    public VendorSummaryDto getVendorSummary(Long vendorId) {
        Vendor vendor = vendorRepository.findByIdAndOrganization_Id(vendorId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Vendor not found with id: " + vendorId));

        VendorSummaryDto summary = new VendorSummaryDto();
        summary.setVendorId(vendorId);
        summary.setVendorName(vendor.getVendorName());

        // Purchase Order aggregates
        Object[] poResult = (Object[]) entityManager
                .createQuery("SELECT COUNT(po), COALESCE(SUM(po.totalAmount), 0) " +
                        "FROM PurchaseOrder po WHERE po.vendor.id = :vendorId")
                .setParameter("vendorId", vendorId)
                .getSingleResult();
        summary.setPurchaseOrderCount((Long) poResult[0]);
        summary.setTotalPurchaseOrderValue((BigDecimal) poResult[1]);

        // Payable aggregates
        Object[] payResult = (Object[]) entityManager
                .createQuery("SELECT COALESCE(SUM(p.amountRecorded), 0), " +
                        "COALESCE(SUM(p.amountPaid), 0), " +
                        "COALESCE(SUM(p.amountRecorded - p.amountPaid), 0) " +
                        "FROM Payable p WHERE p.vendor.id = :vendorId")
                .setParameter("vendorId", vendorId)
                .getSingleResult();
        summary.setTotalAmountRecorded((BigDecimal) payResult[0]);
        summary.setTotalAmountPaid((BigDecimal) payResult[1]);
        summary.setOutstandingAmount((BigDecimal) payResult[2]);

        // GRN aggregates
        Object[] grnResult = (Object[]) entityManager
                .createQuery("SELECT COUNT(grn), COALESCE(SUM(grn.invoiceAmount), 0) " +
                        "FROM GoodsReceivedNote grn WHERE grn.vendor.id = :vendorId")
                .setParameter("vendorId", vendorId)
                .getSingleResult();
        summary.setGrnCount((Long) grnResult[0]);
        // invoiceAmount is Double in entity, convert to BigDecimal
        summary.setTotalInvoiceAmount(BigDecimal.valueOf(((Number) grnResult[1]).doubleValue()));

        return summary;
    }
}
