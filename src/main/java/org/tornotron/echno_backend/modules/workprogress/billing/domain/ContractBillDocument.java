package org.tornotron.echno_backend.modules.workprogress.billing.domain;

import java.util.Set;
import java.util.UUID;
import org.tornotron.echno_backend.common.dto.AttachmentOwner;

/**
 * How a bill's supporting documents (site photos, test reports, delivery challans, measurement
 * sheets) are filed in the shared attachment store: one owner type keyed by the bill's UUID, so
 * the platform's presign, upload and register path works unchanged. Each file carries one of
 * {@link #TYPES} as its document type, which is how the documents tab groups them.
 */
public final class ContractBillDocument {

    public static final String ENTITY_TYPE = "CONTRACT_BILL_DOCUMENT";

    public static final String PHOTO = "photo";
    public static final String TEST_REPORT = "test-report";
    public static final String DELIVERY_CHALLAN = "delivery-challan";
    public static final String MEASUREMENT = "measurement";
    public static final String OTHER = "other";

    public static final Set<String> TYPES = Set.of(PHOTO, TEST_REPORT, DELIVERY_CHALLAN, MEASUREMENT, OTHER);

    private ContractBillDocument() {
    }

    public static AttachmentOwner ownerOf(UUID billId) {
        return AttachmentOwner.of(ENTITY_TYPE, billId);
    }

    public static String folderFor(UUID billId) {
        return ownerOf(billId).folder() + "/bills/" + billId;
    }
}
