package org.tornotron.echno_backend.payable.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.payable.enums.ContractType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * An amount owed to a vendor or contractor, as it is served. A field without
 * {@code nullable = true} is one the schema, the mapper or the entity behind it establishes as
 * always present; see {@code ReviewedResponseSchemas} for which is which.
 */
@Data
public class PayableDto {

    private Long id;

    private String payableNumber;

    private String contractorName;

    @Schema(description = "How the work was contracted. Optional on creation, so null where the "
            + "caller did not classify it.", nullable = true)
    private ContractType contractType;

    @Schema(description = "Amount billed against this payable. Null where nothing has been "
            + "recorded yet, which is not the same as an amount of zero.", nullable = true)
    private BigDecimal amountRecorded;

    @Schema(description = "Amount settled against this payable so far. Null where no payment has "
            + "been recorded, which is not the same as a payment of zero.", nullable = true)
    private BigDecimal amountPaid;

    private BigDecimal amountDue;

    @Schema(description = "Id of the vendor the amount is owed to. Null on a payable raised "
            + "against a contractor who is not a registered vendor.", nullable = true)
    private Long vendorId;

    @Schema(description = "Name of the vendor. Null whenever vendorId is.", nullable = true)
    private String vendorName;

    @Schema(description = "Id of the goods received note this payable was raised from. Null on a "
            + "payable entered directly rather than from a goods received note.",
            nullable = true)
    private Long goodsReceivedNoteId;

    @Schema(description = "Number of that goods received note. Null whenever "
            + "goodsReceivedNoteId is.", nullable = true)
    private String grnNumber;

    @Schema(description = "Id of the project the amount is charged to. The column permits null. "
            + "Every route the application offers requires a project, so a null here names a row "
            + "that did not come through one, such as a database migrated from before the column "
            + "existed.", nullable = true)
    private Long projectId;

    @Schema(description = "Name of the project. Null whenever projectId is, and also on a project "
            + "that has no name recorded.", nullable = true)
    private String projectName;

    @Schema(description = "Employee who raised the payable. Null on rows created before the "
            + "author was recorded, and on any row whose author was not resolved.", nullable = true)
    private EmployeeDto createdBy;

    @Schema(description = "When the payable was recorded. Populated on every insert the "
            + "application makes, but the column permits null, unlike the equivalent on purchase "
            + "orders and receipts, so a row loaded outside the application can carry none.",
            nullable = true)
    private LocalDateTime createdAt;
}
