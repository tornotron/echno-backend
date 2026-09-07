package org.tornotron.echno_backend.architecture;

import org.tornotron.echno_backend.attendance.dto.AttendanceResponseDto;
import org.tornotron.echno_backend.attendance.dto.ClockEventDto;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GoodsReceivedNoteDto;
import org.tornotron.echno_backend.payable.dto.PayableDto;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderDto;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderItemDto;
import org.tornotron.echno_backend.purchaseOrderItem.dto.PurchaseOrderItemResponseDto;
import org.tornotron.echno_backend.receipt.dto.ReceiptDto;
import org.tornotron.echno_backend.vendor.dto.VendorBankAccountDto;
import org.tornotron.echno_backend.vendor.dto.VendorContactDto;
import org.tornotron.echno_backend.vendor.dto.VendorDto;
import org.tornotron.echno_backend.vendor.dto.VendorPaymentTermsDto;
import org.tornotron.echno_backend.vendor.dto.VendorSummaryDto;
import org.tornotron.echno_backend.vendor.dto.VendorTaxIdentifierDto;

import java.util.List;
import java.util.Set;

/**
 * The response schemas whose nullability has been read field by field, and the answer for each
 * field.
 *
 * <h2>What this list is for</h2>
 *
 * <p>#661 made {@code @Schema(nullable = true)} reach the published document, which under
 * OpenAPI 3.1 it never had. What it could not do is say anything about a field nobody has marked.
 * An unmarked property means "no one has looked", and a reader has no way to tell that from
 * "someone looked and it is never null". Across 1987 response-only properties that silence is
 * uniform, so the document is honest but useless on the question.
 *
 * <p>A schema listed here is one where the silence has been made into a claim. Every one of its
 * properties is named below as either nullable or non-null, from the schema, the mapper or the
 * query behind it, so an unmarked field on a listed schema now means the server does not send
 * null there. {@link ResponseNullabilityRatchetTest} holds all three representations to this list:
 * the annotation on the field, the union in {@code docs/openapi.json}, and this table. A property
 * added to a listed schema fails the build until it is classified here, which is the whole point
 * of writing the non-null half down rather than leaving it as the complement.
 *
 * <p>The claim is deliberately narrow. It says the field is not null in a response, and nothing
 * about whether a client may send null in a request: that is the other axis, and the eight
 * partial-update surfaces in {@link PartialUpdateSurfaces} are where it is written down.
 *
 * <h2>How a schema gets added</h2>
 *
 * <p>Per property, in this order, and none of the three is guesswork:
 *
 * <ol>
 *   <li>where the field maps straight through from an entity, the Liquibase column behind it.
 *       Note that the entity's own {@code @Column(nullable = ...)} is not the authority:
 *       {@code ddl-auto} is {@code validate}, and Hibernate's validation does not compare
 *       nullability, so an entity can disagree with the table and nothing will say so;
 *   <li>where the mapper does something other than copy, the mapper;
 *   <li>where the value is computed, the query or service that computes it. A bare SQL
 *       {@code SUM} over no rows is null, so an aggregate is non-null only if something makes it
 *       so.
 * </ol>
 *
 * <p>The vendor module is the first through this, chosen because its five tables are the whole of
 * it and its mappers copy by name, so every answer is checkable rather than argued. Adding a
 * module is per-field reading work with no shortcut, and is worth doing a module at a time so
 * each pass arrives as a diff someone can actually review.
 */
final class ReviewedResponseSchemas {

    private ReviewedResponseSchemas() {
    }

    /**
     * One reviewed schema, split into the two halves that together must account for every
     * property it publishes.
     *
     * <p>Names here are the property names the document uses, which are not always the Java field
     * names: Jackson strips the {@code is} prefix from a primitive boolean, so
     * {@code VendorContactDto.isPrimary} publishes as {@code primary}.
     *
     * @param dto The response DTO class.
     * @param nullable Properties the server may send as null.
     * @param nonNull Properties the server always sends with a value.
     */
    record ReviewedSchema(Class<?> dto, Set<String> nullable, Set<String> nonNull) {
        @Override
        public String toString() {
            return dto.getSimpleName();
        }
    }

    /**
     * Every reviewed response schema.
     *
     * <p><b>Vendor.</b> The five vendor tables are defined in
     * {@code db/changelog/v4.0/baseline-001-level0-tables.xml} and
     * {@code baseline-002-level1-tables.xml} and are not altered afterwards, so the column list
     * there is the whole answer for everything that maps straight through. The mappers copy by
     * name and add nothing.
     *
     * <ul>
     *   <li>{@code VendorDto}: seven optional columns on the {@code vendor} table
     *       ({@code vendor_address}, {@code city}, {@code state}, {@code pin_code},
     *       {@code country}, {@code website}, {@code notes}), plus {@code paymentTerms}, which is
     *       the inverse side of a {@code @OneToOne} the service only populates when the creation
     *       request carried terms and which {@code deletePaymentTerms} removes again. The six
     *       collections are non-null because the entity initialises each to an empty list, so
     *       there is no null for MapStruct to carry across.
     *   <li>{@code VendorContactDto}: {@code alternate_phone} is the one nullable column.
     *   <li>{@code VendorBankAccountDto}: {@code ifsc_code}, {@code account_holder_name} and
     *       {@code swift} are nullable, which matches the domain, since an account is reachable
     *       domestically or internationally and rarely carries codes for both.
     *       {@code account_number} is {@code NOT NULL}; it is stored encrypted through a
     *       converter, and the converter is symmetric, so nothing there can produce a null on the
     *       way out.
     *   <li>{@code VendorPaymentTermsDto}: {@code credit_limit} and {@code credit_days} are
     *       nullable. Both are the case the issue was raised over, where a client writing
     *       {@code ?? 0} on the strength of the document turns "no limit agreed" into "a limit of
     *       zero".
     *   <li>{@code VendorTaxIdentifierDto}: every column is {@code NOT NULL}.
     *   <li>{@code VendorSummaryDto}: computed rather than mapped. Both counts are
     *       {@code COUNT}, and all five money totals are summed inside a
     *       {@code COALESCE(..., 0)}, so none of them can come back null.
     * </ul>
     *
     * <p><b>The procurement chain: purchase order, goods received note, payable.</b> These are
     * the three schemas {@code VendorDto} already publishes as collections, so vendor's pass
     * declared the lists non-null while saying nothing about what is inside them. Every one of
     * them carries money, which is where a wrong answer costs something: a client writing
     * {@code ?? 0} on an amount turns "nobody recorded this" into a figure someone will act on.
     *
     * <ul>
     *   <li>{@code PayableDto.amountDue} is the one property here that is non-null for a reason
     *       that is not a constraint, and it is the {@code VendorSummaryDto} shape: it has no
     *       column at all, and {@code Payable.getAmountDue()} coalesces both operands to
     *       {@code ZERO} before subtracting. Where a nullable column does exist, the column wins
     *       even when the application writes the value on every path. That is the line taken for
     *       {@code PurchaseOrderDto.totalAmount} (seeded at zero on creation, recomputed from a
     *       coalesced {@code SUM}) and for {@code totalPrice} on both line schemas (computed on
     *       every write with a missing unit price read as zero): all three are recorded nullable,
     *       with the computation named in the description, because nothing in the contract can
     *       promise a row was written through the application. The same line makes
     *       {@code PayableDto.createdAt} nullable. In each of these a zero means "no figure was
     *       agreed" rather than "the amount is nothing", which the descriptions say.
     *   <li>{@code items} on {@code PurchaseOrderDto} and {@code GoodsReceivedNoteDto} is
     *       nullable because of the mapper, not the schema. Both entity collections are
     *       initialised and can never be null, and both mappers carry an {@code @AfterMapping}
     *       that replaces an empty list with null on purpose.
     *   <li>{@code projectId} and {@code projectName} are nullable on all three schemas, and this
     *       is the trap the vendor pass warned about. {@code Payable}, {@code PurchaseOrder} and
     *       {@code GoodsReceivedNote} each declare
     *       {@code @JoinColumn(name = "project_id", nullable = false)} while the Liquibase column
     *       is nullable in every case, and no {@code addNotNullConstraint} anywhere in the
     *       changelog names a {@code project_id}. {@code ddl-auto} is {@code validate}, which
     *       does not compare nullability, so nothing reports it. The changelog wins.
     *       {@code projectName} is nullable twice over, since {@code project.project_name} is
     *       itself a nullable column.
     *   <li>A flattened name is null exactly when its foreign key is: {@code vendorName},
     *       {@code grnNumber}, {@code indentNumber}, {@code purchaseOrderNumber},
     *       {@code storageLocationName} and {@code materialName} all map through an association
     *       whose own name column is {@code NOT NULL}.
     *   <li>{@code PayableDto.createdAt} is nullable where the same field on
     *       {@code PurchaseOrderDto} and {@code ReceiptDto} is not. Its column permits null while
     *       theirs do not; {@code @CreationTimestamp} fills it on every insert the application
     *       makes, but the contract cannot promise how a row reached the table. Making
     *       {@code payable.created_at} {@code NOT NULL} would move it to the non-null half.
     *   <li>{@code GoodsReceivedNoteDto.overReceiptAcknowledged} is a primitive {@code boolean} on
     *       the DTO, so Jackson could not emit null for it whatever the source held. The
     *       {@code NOT NULL} column agrees rather than causes it.
     * </ul>
     *
     * <p><b>Attendance.</b> Picked because a wrong answer here has already cost something: a
     * client wrote {@code ?? false} against {@code ClockEventDto.isWithinGeofence} on the
     * strength of the document saying nothing, which turns "the geofence was never evaluated"
     * into "the worker was outside the site".
     *
     * <ul>
     *   <li>{@code isWithinGeofence} is a boxed {@code Boolean} with three states, and null is
     *       the ordinary one: {@code AttendanceService.applyGeofence} evaluates only a
     *       self-marked punch, so every punch a supervisor records for a team member is
     *       unevaluated, as is one with no position, one on a project with no coordinates, and
     *       every event written by a regularization. Changeset {@code 087-01} dropped the old
     *       {@code NOT NULL} and {@code false} default and {@code 087-02} backfilled the existing
     *       rows to null precisely so that a placeholder {@code false} could not be read as a
     *       measured verdict. {@code distanceFromProject} and {@code geofenceRadiusMeters} are
     *       written from the same evaluation, so the three are null together or set together.
     *   <li>The five session-minute fields are nullable for a reason that is neither the column
     *       nor the mapper. {@code Attendance} is a {@code @Builder} class and those five fields
     *       carry an inline {@code = 0} with no {@code @Builder.Default}, so Lombok discards the
     *       initialiser and the builder writes null. Nothing then fills them until
     *       {@code AttendanceCalculationService.recalculate} runs, which never happens on a
     *       record raised by marking someone absent or on leave, or on one with no shift. Null
     *       there means the day was not computed, which is not zero minutes worked.
     *   <li>{@code photoUrl}, {@code verifiedBy} and {@code verifiedAt} on
     *       {@code ClockEventDto} are null on every response ever served. The mapper declares
     *       {@code @Mapping(target = "photoUrl", ignore = true)} and nothing in {@code src/main}
     *       writes the other two on a clock event. They are recorded as nullable and their
     *       descriptions say plainly that they are not populated, rather than implying a flow
     *       that does not exist.
     *   <li>The collections ({@code clockEvents}, {@code regularizations}, {@code movements},
     *       {@code attachments}) are non-null because the entity initialises each and marks it
     *       {@code @Builder.Default}. They are routinely empty, which is a normal state.
     *   <li>{@code createdAt} and {@code updatedAt} are nullable on the same reading as
     *       {@code PayableDto.createdAt}: both columns permit null and only Hibernate's timestamp
     *       callbacks fill them.
     * </ul>
     *
     * <p><b>Receipts.</b> Money received, and the schema where silence was worth the least: 21 of
     * its 25 properties admit null. Two reasons compound. Almost every column on {@code receipts}
     * is nullable, including {@code amount}; and {@code ReceiptService.applyFields} runs on
     * create and update alike and assigns every editable scalar unconditionally, so an update
     * that omits a field clears it rather than leaving it alone ({@code currency} is the one
     * exception, guarded against a null). The five id columns ({@code issuedBy},
     * {@code projectId}, {@code paymentId}, {@code invoiceId}, {@code customerId}) carry no
     * foreign key at all, so a value there may name a row that no longer exists, which the
     * descriptions say.
     *
     * @return The reviewed schemas.
     */
    static List<ReviewedSchema> schemas() {
        return List.of(
                new ReviewedSchema(
                        VendorDto.class,
                        Set.of("vendorAddress", "city", "state", "pinCode", "country", "website",
                                "notes", "paymentTerms"),
                        Set.of("id", "vendorName", "vendorEmail", "type", "status",
                                "goodsReceivedNotes", "purchaseOrders", "payables", "contacts",
                                "taxIdentifiers", "bankAccounts")),
                new ReviewedSchema(
                        VendorContactDto.class,
                        Set.of("alternatePhone"),
                        Set.of("id", "contactPerson", "email", "phone", "primary")),
                new ReviewedSchema(
                        VendorBankAccountDto.class,
                        Set.of("ifscCode", "accountHolderName", "swift"),
                        Set.of("id", "bankName", "accountNumber", "default")),
                new ReviewedSchema(
                        VendorPaymentTermsDto.class,
                        Set.of("creditLimit", "creditDays"),
                        Set.of("id", "paymentTerms")),
                new ReviewedSchema(
                        VendorTaxIdentifierDto.class,
                        Set.of(),
                        Set.of("id", "type", "value")),
                new ReviewedSchema(
                        VendorSummaryDto.class,
                        Set.of(),
                        Set.of("vendorId", "vendorName", "purchaseOrderCount",
                                "totalPurchaseOrderValue", "totalAmountRecorded",
                                "totalAmountPaid", "outstandingAmount", "grnCount",
                                "totalInvoiceAmount")),
                new ReviewedSchema(
                        PurchaseOrderDto.class,
                        Set.of("indentId", "indentNumber", "projectId", "projectName",
                                "createdBy", "expectedDeliveryDate", "remarks", "items",
                                "totalAmount"),
                        Set.of("id", "poNumber", "vendorId", "vendorName", "status",
                                "createdAt")),
                new ReviewedSchema(
                        PurchaseOrderItemDto.class,
                        Set.of("indentItemId", "unitPrice", "totalPrice", "remarks"),
                        Set.of("id", "materialId", "materialName", "orderedQuantity",
                                "receivedQuantity")),
                new ReviewedSchema(
                        PurchaseOrderItemResponseDto.class,
                        Set.of("indentItemId", "unitPrice", "totalPrice", "remarks"),
                        Set.of("id", "purchaseOrderId", "poNumber", "materialId", "materialName",
                                "orderedQuantity", "receivedQuantity")),
                new ReviewedSchema(
                        GoodsReceivedNoteDto.class,
                        Set.of("receivedBy", "vendorId", "vendorName", "purchaseOrderId",
                                "purchaseOrderNumber", "deliveryChallanNumber", "invoiceNumber",
                                "invoiceAmount", "projectId", "projectName", "storageLocationId",
                                "storageLocationName", "items"),
                        Set.of("id", "grnNumber", "receivedOn", "overReceiptAcknowledged")),
                new ReviewedSchema(
                        PayableDto.class,
                        Set.of("contractType", "amountRecorded", "amountPaid", "vendorId",
                                "vendorName", "goodsReceivedNoteId", "grnNumber", "projectId",
                                "projectName", "createdBy", "createdAt"),
                        Set.of("id", "payableNumber", "contractorName", "amountDue")),
                new ReviewedSchema(
                        ReceiptDto.class,
                        Set.of("type", "status", "amount", "currency", "receiptDate",
                                "paymentMethod", "transactionId", "referenceNumber",
                                "receivedFrom", "receivedFromAddress", "taxAmount", "taxRate",
                                "taxType", "description", "notes", "issuedBy", "projectId",
                                "paymentId", "invoiceId", "customerId", "organizationId"),
                        Set.of("id", "receiptNumber", "createdAt", "updatedAt")),
                new ReviewedSchema(
                        AttendanceResponseDto.class,
                        Set.of("shiftTiming", "totalWorkMinutes", "morningSessionMinutes",
                                "afternoonSessionMinutes", "overtimeMinutes",
                                "breakDurationMinutes", "leaveId", "leaveType", "approvedBy",
                                "approvedById", "approvedAt", "geofenceApproverId", "remarks",
                                "createdAt", "updatedAt"),
                        Set.of("id", "employeeId", "employeeName", "attendanceDate", "projectId",
                                "projectName", "status", "clockEvents", "isLateArrival",
                                "isEarlyCheckout", "isOvertime", "regularizations", "movements",
                                "approvalStatus", "requiresGeofenceApproval")),
                new ReviewedSchema(
                        ClockEventDto.class,
                        Set.of("latitude", "longitude", "gpsAccuracy", "photoUrl",
                                "devicePlatform", "isWithinGeofence", "distanceFromProject",
                                "geofenceRadiusMeters", "geofenceExceptionReason", "recordedById",
                                "remarks", "verifiedBy", "verifiedAt", "regularizationReason"),
                        Set.of("id", "eventType", "eventTimestamp", "projectId", "projectName",
                                "isRegularized", "attachments")));
    }
}
