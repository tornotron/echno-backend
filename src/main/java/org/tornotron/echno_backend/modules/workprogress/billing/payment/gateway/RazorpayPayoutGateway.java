package org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.tornotron.echno_backend.billing.gateway.BillingGatewayException;
import org.tornotron.echno_backend.billing.gateway.razorpay.RazorpayRestClient;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.BillPaymentAmounts;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.BillPaymentProvider;

/**
 * Pays a contractor through Razorpay's payout product (RazorpayX): a contact, a bank fund account on
 * it, and a payout from the paying organization's own RazorpayX account. Razorpay answers with the
 * existing contact or fund account when the details repeat, so nothing here is stored but the payout
 * id.
 *
 * <p>The payment id goes out as {@code X-Payout-Idempotency} and as {@code reference_id}: the first
 * makes a repeated send the same payout, the second is how a webhook finds its payment.
 *
 * <p>Errors from Razorpay name the endpoint and the account, so they stay in the cause chain for the
 * log. The message here is what shows on the payment.
 */
public class RazorpayPayoutGateway implements BillPaymentGateway {

    /** RTGS starts at two lakh rupees; below it the transfer goes by NEFT. */
    static final BigDecimal RTGS_FROM = new BigDecimal("200000.00");

    private static final int MAX_NARRATION = 30;
    private static final String UNUSABLE = "Razorpay gave no usable answer; send it again with Sync, or check the Razorpay dashboard";

    private final RazorpayRestClient client;
    private final String accountNumber;

    public RazorpayPayoutGateway(RazorpayRestClient client, String accountNumber) {
        this.client = client;
        this.accountNumber = accountNumber;
    }

    @Override
    public BillPaymentProvider provider() {
        return BillPaymentProvider.RAZORPAY;
    }

    @Override
    public PayoutResult send(PayoutCommand command) {
        try {
            String contactId = requireId(client.post("/contacts", contact(command)));
            String fundAccountId = requireId(client.post("/fund_accounts", fundAccount(contactId, command)));
            JsonNode payout = client.post("/payouts", payout(fundAccountId, command),
                    Map.of("X-Payout-Idempotency", command.paymentId().toString()));
            return toResult(payout);
        } catch (BillingGatewayException e) {
            throw new BillPaymentGatewayException(UNUSABLE, e);
        }
    }

    @Override
    public PayoutResult fetch(PayoutLookup lookup) {
        if (lookup.providerRef() == null) {
            throw new BillPaymentGatewayException("This payment has no Razorpay payout id yet; send it again with Sync");
        }
        try {
            return toResult(client.get("/payouts/" + lookup.providerRef()));
        } catch (BillingGatewayException e) {
            throw new BillPaymentGatewayException(UNUSABLE, e);
        }
    }

    /** What a payout says about itself, from a response or a webhook's payout entity. */
    public static PayoutResult toResult(JsonNode payout) {
        String id = text(payout, "id");
        String status = text(payout, "status");
        if (id == null || status == null) {
            throw new BillPaymentGatewayException(UNUSABLE);
        }
        return new PayoutResult(outcome(status), id, text(payout, "utr"), failureReason(payout),
                payout.hasNonNull("amount") ? payout.get("amount").asLong() : null);
    }

    private static PayoutResult.Outcome outcome(String status) {
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "processed" -> PayoutResult.Outcome.SETTLED;
            case "reversed" -> PayoutResult.Outcome.REVERSED;
            case "failed", "rejected", "cancelled" -> PayoutResult.Outcome.FAILED;
            // queued, pending, processing: still with the provider, and anything new reads the same.
            default -> PayoutResult.Outcome.PROCESSING;
        };
    }

    private static String failureReason(JsonNode payout) {
        JsonNode details = payout.path("status_details");
        String description = text(details, "description");
        return description != null ? description : text(payout, "failure_reason");
    }

    private static Map<String, Object> contact(PayoutCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", command.beneficiaryName());
        body.put("type", "vendor");
        if (command.contactEmail() != null && !command.contactEmail().isBlank()) {
            body.put("email", command.contactEmail().trim());
        }
        if (command.contactPhone() != null && !command.contactPhone().isBlank()) {
            body.put("contact", command.contactPhone().trim());
        }
        return body;
    }

    private static Map<String, Object> fundAccount(String contactId, PayoutCommand command) {
        Map<String, Object> bank = new LinkedHashMap<>();
        bank.put("name", command.beneficiaryName());
        bank.put("ifsc", command.ifsc());
        bank.put("account_number", command.accountNumber());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contact_id", contactId);
        body.put("account_type", "bank_account");
        body.put("bank_account", bank);
        return body;
    }

    private Map<String, Object> payout(String fundAccountId, PayoutCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("account_number", accountNumber);
        body.put("fund_account_id", fundAccountId);
        body.put("amount", BillPaymentAmounts.toPaise(command.amount()));
        body.put("currency", "INR");
        body.put("mode", command.amount().compareTo(RTGS_FROM) >= 0 ? "RTGS" : "NEFT");
        body.put("purpose", "vendor bill");
        body.put("queue_if_low_balance", true);
        body.put("reference_id", command.paymentId().toString());
        body.put("narration", narration(command.narration()));
        return body;
    }

    /** Razorpay takes letters, digits and spaces here, up to {@value #MAX_NARRATION} characters. */
    static String narration(String text) {
        String clean = text == null ? "" : text.replaceAll("[^A-Za-z0-9 ]", " ").trim();
        return clean.length() <= MAX_NARRATION ? clean : clean.substring(0, MAX_NARRATION).trim();
    }

    private static String requireId(JsonNode entity) {
        String id = text(entity, "id");
        if (id == null) {
            throw new BillPaymentGatewayException(UNUSABLE);
        }
        return id;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
}
