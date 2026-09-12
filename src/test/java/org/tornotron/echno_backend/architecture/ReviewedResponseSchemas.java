package org.tornotron.echno_backend.architecture;

import org.tornotron.echno_backend.attendance.dto.AttendanceResponseDto;
import org.tornotron.echno_backend.attendance.dto.ClockEventDto;
import org.tornotron.echno_backend.finance.bank.dtos.CompanyBankAccountDto;
import org.tornotron.echno_backend.finance.budget.dtos.BudgetAllocationDto;
import org.tornotron.echno_backend.finance.budget.dtos.CostCategoryDto;
import org.tornotron.echno_backend.finance.budget.dtos.ProjectCostControlDto;
import org.tornotron.echno_backend.finance.budget.dtos.ProjectCostControlLineDto;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionInvoiceDto;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionInvoiceLineDto;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionPaymentDto;
import org.tornotron.echno_backend.finance.invoice.dtos.InvoiceDto;
import org.tornotron.echno_backend.finance.invoice.dtos.InvoiceLineDto;
import org.tornotron.echno_backend.finance.ledger.dtos.AccountDto;
import org.tornotron.echno_backend.finance.ledger.dtos.AccountTreeDto;
import org.tornotron.echno_backend.finance.ledger.dtos.AddressDto;
import org.tornotron.echno_backend.finance.ledger.dtos.CustomerDto;
import org.tornotron.echno_backend.finance.ledger.dtos.JournalEntryDto;
import org.tornotron.echno_backend.finance.ledger.dtos.JournalEntryLineDto;
import org.tornotron.echno_backend.finance.payment.dtos.PaymentDto;
import org.tornotron.echno_backend.finance.posting.dtos.PostingAccountMappingDto;
import org.tornotron.echno_backend.finance.settings.dtos.FinanceSettingsDto;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GoodsReceivedNoteDto;
import org.tornotron.echno_backend.modules.inspections.dtos.ChecklistTemplateDto;
import org.tornotron.echno_backend.modules.inspections.dtos.ChecklistTemplateItemDto;
import org.tornotron.echno_backend.modules.inspections.dtos.DefectPhotoAnnotationDto;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionCheckItemDto;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionDefectDto;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionDto;
import org.tornotron.echno_backend.modules.inspections.dtos.NcrDto;
import org.tornotron.echno_backend.modules.inspections.dtos.StarterChecklistTemplateDto;
import org.tornotron.echno_backend.leave.dto.LeaveApprovalDto;
import org.tornotron.echno_backend.leave.dto.LeaveBalanceDto;
import org.tornotron.echno_backend.leave.dto.LeaveBalanceSummaryDto;
import org.tornotron.echno_backend.leave.dto.LeaveCalendarDto;
import org.tornotron.echno_backend.leave.dto.LeavePolicyDto;
import org.tornotron.echno_backend.leave.dto.LeavePolicySimpleDto;
import org.tornotron.echno_backend.leave.dto.LeaveRequestDto;
import org.tornotron.echno_backend.leave.dto.LeaveTransactionDto;
import org.tornotron.echno_backend.leave.dto.NotificationDto;
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
     *       {@code GoodsReceivedNote} each declared
     *       {@code @JoinColumn(name = "project_id", nullable = false)} while the Liquibase column
     *       is nullable in every case, and no {@code addNotNullConstraint} anywhere in the
     *       changelog names a {@code project_id}. {@code ddl-auto} is {@code validate}, which
     *       does not compare nullability, so nothing reported it. The changelog won, and #728
     *       dropped the three annotations to match rather than tightening the columns, which
     *       would need a null sweep of every deployed database first;
     *       {@code ProjectJoinColumnMatchesTheChangelogTest} now reads the expectation out of the
     *       changeset. {@code projectName} is nullable twice over, since
     *       {@code project.project_name} is itself a nullable column.
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
     *   <li>The five session-minute fields stay nullable, but no longer for the reason the
     *       original pass gave. {@code Attendance} is a {@code @Builder} class and the five
     *       carried an inline {@code = 0} with no {@code @Builder.Default}, so Lombok discarded
     *       the initialiser and the builder wrote null on the one path that never recalculates:
     *       marking someone absent or on leave, which also leaves the record without a shift and
     *       so beyond the reach of every later {@code recalculate} call, all of which are guarded
     *       on the shift. #728 added the annotation, so those records now store the zero the
     *       declaration always intended, and match a computed day on which nobody punched. The
     *       properties remain nullable because the rows written before that are still in the
     *       table and were not rewritten.
     *   <li>{@code photoUrl}, {@code verifiedBy} and {@code verifiedAt} were null on every
     *       response the server had ever produced, and #728 took them off
     *       {@code ClockEventDto} rather than leave the contract promising them.
     *       {@code photoUrl} was the leftover half of a finished migration to
     *       {@code Attachment}, and the punch photos it named are returned in
     *       {@code attachments}; the other two were the unstarted half of a per-punch
     *       verification that {@code MovementRecord} has and a clock event never grew.
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
     * <p><b>Finance.</b> The largest untouched block, and the one where a wrong answer is most
     * expensive: the chart of accounts, the ledger, customer invoicing and receipts, construction
     * invoices and payment vouchers, budget heads and the project cost-control view. Twenty
     * schemas, 218 properties, 85 of them nullable.
     *
     * <ul>
     *   <li>The entities agree with the changelog throughout, so there is no finding here of the
     *       kind the procurement chain produced. Every straight-through property was read off the
     *       Liquibase column, and no {@code @Column} or {@code @JoinColumn} in the module
     *       contradicts one.
     *   <li>The money on a construction invoice is non-null where the money on a purchase order
     *       was not, which reads as an inconsistency and is not one. {@code subtotal},
     *       {@code taxAmount}, {@code discountAmount}, {@code totalAmount}, {@code paidAmount}
     *       and {@code balanceAmount} sit on {@code NOT NULL} columns, so the constraint carries
     *       the claim and the code that writes them does not have to.
     *   <li>{@code InvoiceDto.balanceDue} is the computed-with-no-column case, the third instance
     *       of the {@code VendorSummaryDto} and {@code PayableDto.amountDue} shape:
     *       {@code Invoice.balanceDue()} subtracts one {@code NOT NULL} column from another, so
     *       nothing in it can produce a null.
     *   <li>{@code ProjectCostControlDto} and its lines are computed end to end and read no
     *       column directly. Every figure passes through {@code MoneyUtils.normalize}, which
     *       turns a null into zero; the head name falls back to {@code (unknown)} and the totals
     *       row is labelled. Only {@code costCategoryId} admits null, and only on that totals
     *       row, which is what its null means.
     *   <li>{@code PostingAccountMappingDto} is non-null throughout because
     *       {@code PostingAccountResolver.resolveWithSource} either returns an account or throws
     *       {@code AccountNotFoundException}. A role with no mapping falls back to the configured
     *       default code, and a default code that resolves to nothing is an error rather than a
     *       null account.
     *   <li>{@code CustomerDto.billingAddress} is nullable for a reason that is neither a column
     *       nor a mapper. {@code Address} is an {@code @Embedded} value whose seven columns are
     *       all nullable, and Hibernate leaves the embedded object null when every one of them
     *       is, whatever the field initialiser on {@code Customer} says. Its own seven properties
     *       are then nullable for the ordinary reason, which makes {@code AddressDto} the one
     *       schema here with nothing at all in its non-null half.
     *   <li>A flattened name is null exactly when its foreign key is, as in procurement.
     *       {@code ledgerAccountCode}, {@code ledgerAccountName}, {@code customerName},
     *       {@code accountCode} and {@code accountName} on a journal line, {@code bankName} and
     *       {@code bankAccountNumber} on a payment, and {@code revenueAccountCode} all reach a
     *       {@code NOT NULL} column through a {@code NOT NULL} join, so every one of them is
     *       non-null. {@code costCategoryName} on a construction invoice line is the mirror
     *       image: its join column is nullable, so the name is too.
     *   <li>{@code ConstructionPaymentDto} admits null on 22 of its 31 properties, which is the
     *       schema's shape rather than an omission. One voucher names a payee of one kind out of
     *       five and carries a column for each of them, records bank details only where a bank
     *       was used, and stamps a verifier only once verified.
     *   <li>{@code JournalEntryDto.createdBy} is nullable where {@code createdAt} on the same
     *       schema is not. The two auditing columns disagree in the changelog:
     *       {@code created_at} is {@code NOT NULL} and {@code created_by} is not.
     * </ul>
     *
     * <p><b>Leave.</b> The seven leave tables are created across {@code v1.2} and {@code v1.3} and
     * consolidated in the {@code v4.0} baseline. Both paths were read, because a column that is
     * nullable on one install and not on the other would make the answer depend on how the
     * database was built; they agree, and every {@code @Column} and {@code @JoinColumn} in the
     * module matches its changeset. {@code multi_level_approval_enabled} arrives later, in
     * {@code v4.0/039}, as {@code NOT NULL DEFAULT true}, so it is non-null on every install path.
     *
     * <ul>
     *   <li>{@code LeavePolicyDto} is nullable on 14 of its 24 properties, which is the schema's
     *       shape rather than an omission. A policy carries one column for each rule an
     *       organization may choose not to set, and most organizations set few of them.
     *   <li>{@code LeaveBalanceDto.available} and {@code bookable} are the
     *       {@code VendorSummaryDto} shape again: {@code @Transient} getters over
     *       {@code LeaveDays.round} arithmetic on columns that are all {@code NOT NULL}, with no
     *       column of their own.
     *   <li>{@code LeaveBalanceSummaryDto} has nothing in its nullable half. It is assembled
     *       field by field in {@code LeaveBalanceService.getBalanceSummary}, the totals are
     *       {@code LeaveDays.round} over a stream sum, and the controller defaults {@code year}
     *       to the current year when the request omits it.
     *   <li>Three names are null because the mapper ignores them and no service fills them in:
     *       {@code delegatedFromName} and {@code createdByName} are structurally always null, and
     *       {@code handoverToName} is resolved on the single-request read alone. They are
     *       classified as nullable because that is what the server sends, and filed as defects on
     *       #741 because it is not what the code means. #741 repaired the part of that which is
     *       an isolation defect, the unscoped {@code findById} behind {@code handoverToName}, and
     *       left the three names unpopulated: {@code delegatedFromId}, {@code createdById} and
     *       {@code handoverToId} are all published beside them and the description of each name
     *       already directs the client to resolve from the id, so filling them in is a feature
     *       across fourteen read paths and one nested list, and dropping them from a
     *       hand-maintained contract breaks web at runtime. Both directions are decisions for the
     *       contract owners rather than repairs, and the classification stands either way.
     *   <li>{@code currentApproverId}, {@code currentApproverName}, {@code currentApprovalLevel}
     *       and {@code maxApprovalLevel} are cleared or never written by the workflow rather than
     *       by a constraint: the first two are nulled on approve, reject, cancel and withdraw,
     *       and the levels are written only when the approval chain is built at submission.
     *   <li>{@code carryForwardFromPrevious} and {@code LeaveTransactionDto.description} are the
     *       standing line held again: the application writes both on every path, the column
     *       permits null, so they stay nullable with the writing named in the description.
     *   <li>The module contains no {@code @Builder}, {@code @Embedded} or {@code @Embeddable}, so
     *       neither the attendance trap nor the {@code CustomerDto.billingAddress} one has an
     *       analogue here. It does have a setter-shaped equivalent, on #741:
     *       {@code createPolicy} calls every setter unconditionally, so an explicit null in the
     *       payload overwrites the entity's own initialiser.
     * </ul>
     *
     * <p><b>Inspection.</b> Two things shape this module and neither is visible from the entity
     * alone: an inspection row has two producers, a person and the compliance generator, and a
     * migration relaxed the schema for the second of them.
     *
     * <ul>
     *   <li>{@code scheduledDate} and {@code inspectorId} are nullable even though
     *       {@code CreateInspectionRequest} and {@code UpdateInspectionRequest} both declare them
     *       {@code @NotNull}. {@code 035-relax-inspection-not-null-for-ai-rows} dropped the
     *       constraint precisely so {@code ComplianceGenerationService} can write a row without
     *       either. The constraint lives on the payload rather than on the row, so no API caller
     *       can omit them and rows exist that do.
     *   <li>{@code compliancePhase}, {@code riskLevel}, {@code resolutionOptions},
     *       {@code complianceRuleRef} and {@code aiRationale} are written only by that same
     *       service, so they are null on every manually created inspection. The first two also
     *       fall back to the rule's own default, which can itself be null.
     *   <li>{@code InspectionCheckItemDto.deviation} is a computation that returns null:
     *       {@code MeasurementDeviation.of} yields nothing unless the measurement and the
     *       expected value both parse as numbers in the same unit.
     *   <li>{@code raisedById}, {@code verifiedById}, {@code closedById} and
     *       {@code DefectPhotoAnnotationDto.createdById} come from {@code currentEmployeeId()},
     *       which returns null when the signed-in user has no employee row in the current
     *       organization. This is the {@code PostingAccountMappingDto} resolver shape with the
     *       opposite ending: it returns null where the posting resolver throws.
     *   <li>{@code InspectionDefectDto.severity} is nullable because of a migration rather than a
     *       design: {@code 054-03} cleared blank values to null rather than guessing a bucket.
     *   <li>{@code priority} on both check-item schemas and {@code InspectionDefectDto.status}
     *       are the standing line again. The service substitutes {@code medium} and {@code OPEN}
     *       on every call site, the columns permit null, so they stay nullable with the
     *       substitution named.
     *   <li>Nothing in the module is non-null by aggregate: there is no {@code COALESCE} and no
     *       normalizer. The seven collection properties are Hibernate-managed collections
     *       initialised on the entity and copied across by MapStruct, and the four counts are
     *       primitive {@code int} over {@code NOT NULL} columns.
     *   <li>Three entities declare a bare {@code @JoinColumn} over a {@code NOT NULL}
     *       {@code organization_id}. That is the safe direction of the disagreement the
     *       procurement pass found, so it breaks nothing today; it is on #741 because
     *       {@code validate} catches neither direction.
     * </ul>
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
                        Set.of("latitude", "longitude", "gpsAccuracy",
                                "devicePlatform", "isWithinGeofence", "distanceFromProject",
                                "geofenceRadiusMeters", "geofenceExceptionReason", "recordedById",
                                "remarks", "regularizationReason"),
                        Set.of("id", "eventType", "eventTimestamp", "projectId", "projectName",
                                "isRegularized", "attachments")),
                new ReviewedSchema(
                        AccountDto.class,
                        Set.of("description", "parentId"),
                        Set.of("id", "code", "name", "type", "active")),
                new ReviewedSchema(
                        AccountTreeDto.class,
                        Set.of("description"),
                        Set.of("id", "code", "name", "type", "active", "postable", "children")),
                new ReviewedSchema(
                        AddressDto.class,
                        Set.of("city", "country", "line1", "line2", "postalCode", "state",
                                "stateCode"),
                        Set.of()),
                new ReviewedSchema(
                        CustomerDto.class,
                        Set.of("billingAddress", "creditLimit", "email", "gstin", "pan",
                                "paymentTermsDays", "phone"),
                        Set.of("id", "code", "name", "active")),
                new ReviewedSchema(
                        JournalEntryDto.class,
                        Set.of("createdBy", "reference", "reversedByEntryId", "reversesEntryId",
                                "sourceId", "sourceType"),
                        Set.of("id", "entryNumber", "entryDate", "description", "status", "lines",
                                "createdAt")),
                new ReviewedSchema(
                        JournalEntryLineDto.class,
                        Set.of("narration"),
                        Set.of("id", "accountId", "accountCode", "accountName", "debit", "credit",
                                "lineOrder")),
                new ReviewedSchema(
                        InvoiceDto.class,
                        Set.of("journalEntryId", "notes", "reversalJournalEntryId"),
                        Set.of("id", "invoiceNumber", "customerId", "customerName", "invoiceDate",
                                "dueDate", "status", "subtotal", "taxTotal", "total", "amountPaid",
                                "balanceDue", "lines")),
                new ReviewedSchema(
                        InvoiceLineDto.class,
                        Set.of(),
                        Set.of("id", "description", "quantity", "unitPrice", "lineSubtotal",
                                "taxRate", "taxAmount", "lineTotal", "revenueAccountId",
                                "revenueAccountCode")),
                new ReviewedSchema(
                        PaymentDto.class,
                        Set.of("externalReference", "journalEntryId", "notes"),
                        Set.of("id", "paymentNumber", "customerId", "customerName", "paymentDate",
                                "amount", "companyBankAccountId", "bankName", "bankAccountNumber",
                                "allocations")),
                new ReviewedSchema(
                        PaymentDto.AllocationDto.class,
                        Set.of(),
                        Set.of("id", "invoiceId", "invoiceNumber", "allocatedAmount")),
                new ReviewedSchema(
                        CompanyBankAccountDto.class,
                        Set.of("ifscCode", "swiftCode"),
                        Set.of("id", "bankName", "accountNumber", "accountHolderName", "isDefault",
                                "active", "ledgerAccountId", "ledgerAccountCode", "ledgerAccountName")),
                new ReviewedSchema(
                        ConstructionInvoiceDto.class,
                        Set.of("approvedAt", "approvedBy", "approvedByName", "arInvoiceId",
                                "goodsReceiptId", "gstNumber", "journalEntryId", "notes",
                                "paymentDate", "paymentMethod", "paymentRecordedBy",
                                "paymentRecordedByName", "paymentTerms", "purchaseOrderId",
                                "reversalJournalEntryId", "submittedAt", "submittedBy",
                                "submittedByName", "taxType", "termsAndConditions", "vendorId"),
                        Set.of("id", "invoiceNumber", "type", "status", "paymentStatus",
                                "projectId", "issueDate", "dueDate", "subtotal", "taxAmount",
                                "discountAmount", "totalAmount", "paidAmount", "balanceAmount",
                                "lines")),
                new ReviewedSchema(
                        ConstructionInvoiceLineDto.class,
                        Set.of("assetId", "costCategoryId", "costCategoryName", "inventoryItemId",
                                "taskId"),
                        Set.of("id", "description", "quantity", "unit", "unitPrice", "taxRate",
                                "taxAmount", "discountRate", "discountAmount", "subtotal", "total")),
                new ReviewedSchema(
                        ConstructionPaymentDto.class,
                        Set.of("accountNumber", "bankName", "cancellationReason", "description",
                                "employeeId", "ifscCode", "invoiceId", "labourId", "notes",
                                "payeeDetails", "payeeName", "payeeType", "purchaseOrderId",
                                "raisedBy", "raisedByName", "referenceNumber", "subContractId",
                                "transactionId", "vendorId", "verifiedAt", "verifiedBy",
                                "verifiedByName"),
                        Set.of("id", "paymentNumber", "type", "status", "method", "projectId",
                                "amount", "currency", "paymentDate")),
                new ReviewedSchema(
                        CostCategoryDto.class,
                        Set.of("code", "expenseAccountCode", "expenseAccountId"),
                        Set.of("id", "name", "active")),
                new ReviewedSchema(
                        BudgetAllocationDto.class,
                        Set.of(),
                        Set.of("id", "projectId", "costCategoryId", "costCategoryName",
                                "allocatedAmount")),
                new ReviewedSchema(
                        ProjectCostControlDto.class,
                        Set.of(),
                        Set.of("projectId", "categories", "totals")),
                new ReviewedSchema(
                        ProjectCostControlLineDto.class,
                        Set.of("costCategoryId"),
                        Set.of("costCategoryName", "allocated", "committed", "spent", "remaining",
                                "overBudget")),
                new ReviewedSchema(
                        PostingAccountMappingDto.class,
                        Set.of(),
                        Set.of("role", "source", "accountId", "accountCode", "accountName")),
                new ReviewedSchema(
                        FinanceSettingsDto.class,
                        Set.of("approvalThreshold"),
                        Set.of()),
                new ReviewedSchema(
                        LeaveApprovalDto.class,
                        Set.of("actionAt", "approverDesignation", "comments", "delegatedFromId",
                                "delegatedFromName"),
                        Set.of("action", "approvalLevel", "approverId", "approverName", "createdAt",
                                "id", "leaveRequestId")),
                new ReviewedSchema(
                        LeaveBalanceDto.class,
                        Set.of("carryForwardExpiryDate", "carryForwardFromPrevious",
                                "lastCalculatedAt"),
                        Set.of("accrued", "available", "bookable", "employeeId", "employeeName",
                                "id", "leavePolicy", "openingBalance", "pending", "used", "year")),
                new ReviewedSchema(
                        LeaveBalanceSummaryDto.class,
                        Set.of(),
                        Set.of("balances", "employeeId", "employeeName", "totalAvailable",
                                "totalPending", "totalUsed", "year")),
                new ReviewedSchema(
                        LeaveCalendarDto.class,
                        Set.of("department"),
                        Set.of("dayType", "employeeId", "employeeName", "id", "leaveDate",
                                "leaveRequestId", "leaveTypeCode", "leaveTypeName",
                                "organizationId")),
                new ReviewedSchema(
                        LeavePolicyDto.class,
                        Set.of("accrualRatePerMonth", "advanceNoticeDays", "allowHalfDay",
                                "applicableGenders", "attachmentRequiredAfterDays",
                                "carryForwardExpiryMonths", "carryForwardLimit", "description",
                                "displayOrder", "isPaid", "maxDaysPerRequest", "minDaysPerRequest",
                                "minServiceMonths", "requiresAttachment"),
                        Set.of("annualQuota", "createdAt", "id", "isActive", "leaveTypeCode",
                                "leaveTypeName", "multiLevelApprovalEnabled", "organizationId",
                                "organizationName", "updatedAt")),
                new ReviewedSchema(
                        LeavePolicySimpleDto.class,
                        Set.of("allowHalfDay", "isPaid"),
                        Set.of("annualQuota", "id", "leaveTypeCode", "leaveTypeName")),
                new ReviewedSchema(
                        LeaveRequestDto.class,
                        Set.of("cancellationReason", "cancelledAt", "contactDuringLeave",
                                "currentApprovalLevel", "currentApproverId", "currentApproverName",
                                "department", "endHalfDayType", "handoverNotes", "handoverToId",
                                "handoverToName", "maxApprovalLevel", "startHalfDayType"),
                        Set.of("approvals", "createdAt", "employeeId", "employeeName", "endDate",
                                "id", "leavePolicy", "organizationId", "reason", "requestNumber",
                                "startDate", "status", "totalDays", "updatedAt")),
                new ReviewedSchema(
                        LeaveTransactionDto.class,
                        Set.of("createdById", "createdByName", "description", "leaveRequestId",
                                "referenceMonth", "referenceYear", "requestNumber"),
                        Set.of("balanceAfter", "balanceBefore", "createdAt", "days", "employeeId",
                                "employeeName", "id", "leaveBalanceId", "leaveTypeName",
                                "transactionDate", "transactionType")),
                new ReviewedSchema(
                        NotificationDto.class,
                        Set.of("actionUrl", "entityId", "entityType", "readAt"),
                        Set.of("createdAt", "id", "isRead", "message", "notificationType",
                                "recipientId", "title")),
                new ReviewedSchema(
                        InspectionDto.class,
                        Set.of("actualEndTime", "actualStartTime", "aiRationale", "areaInspected",
                                "clientRepresentative", "compliancePhase", "complianceRuleRef",
                                "contractorId", "drawingReference", "duration", "inspectorId",
                                "location", "projectId", "resolutionOptions", "result", "riskLevel",
                                "scheduledDate", "scheduledTime", "spatialNodeId", "temperature",
                                "trade", "tradeGroup", "tradeId", "tradeName", "weatherConditions"),
                        Set.of("attendees", "category", "checkItems", "createdAt", "defects",
                                "defectsFound", "failedCheckPoints", "id", "inspectionNumber",
                                "origin", "passedCheckPoints", "spatialPath", "status", "title",
                                "totalCheckPoints", "type", "updatedAt")),
                new ReviewedSchema(
                        InspectionCheckItemDto.class,
                        Set.of("acceptanceCriterion", "bimElementGuid", "deviation",
                                "expectedValue", "measurement", "priority", "remarks",
                                "spatialNodeId", "specification", "tolerance"),
                        Set.of("category", "checkPoint", "id", "photos", "photosRequired",
                                "spatialPath", "status")),
                new ReviewedSchema(
                        InspectionDefectDto.class,
                        Set.of("category", "location", "observationId", "resolvedDate", "responsibleParty",
                                "severity", "spatialNodeId", "status", "targetDate"),
                        Set.of("correctiveAction", "description", "id", "photos", "spatialPath")),
                new ReviewedSchema(
                        NcrDto.class,
                        Set.of("closedAt", "closedById", "correctiveActionCompletedAt",
                                "correctiveActionRemarks", "defectId", "observationId", "raisedById",
                                "severity", "siteEngineerId", "targetDate", "verificationRemarks",
                                "verifiedAt", "verifiedById"),
                        Set.of("createdAt", "description", "id", "inspectionId", "ncrNumber",
                                "status", "title", "type", "updatedAt")),
                new ReviewedSchema(
                        DefectPhotoAnnotationDto.class,
                        Set.of("createdById", "label"),
                        Set.of("id", "inspectionId", "lineOrder", "photo", "shape", "x1", "x2",
                                "y1", "y2")),
                new ReviewedSchema(
                        ChecklistTemplateDto.class,
                        Set.of("applicableElementTypes", "applicableProjectTypes", "description"),
                        Set.of("active", "createdAt", "id", "items", "name", "trade", "tradeGroup",
                                "tradeId", "tradeName", "updatedAt", "version")),
                new ReviewedSchema(
                        ChecklistTemplateItemDto.class,
                        Set.of("acceptanceCriterion", "expectedValue", "priority", "specification",
                                "tolerance"),
                        Set.of("category", "checkPoint", "id", "lineOrder", "photosRequired")),
                new ReviewedSchema(
                        StarterChecklistTemplateDto.class,
                        Set.of("description"),
                        Set.of("id", "items", "name", "trade")));
    }
}
