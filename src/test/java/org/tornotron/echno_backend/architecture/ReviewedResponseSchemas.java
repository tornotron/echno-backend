package org.tornotron.echno_backend.architecture;

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
                                "totalInvoiceAmount")));
    }
}
