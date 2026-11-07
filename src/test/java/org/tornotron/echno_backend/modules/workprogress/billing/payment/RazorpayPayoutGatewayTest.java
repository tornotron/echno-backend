package org.tornotron.echno_backend.modules.workprogress.billing.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.billing.gateway.BillingGatewayException;
import org.tornotron.echno_backend.billing.gateway.BillingGatewayProperties;
import org.tornotron.echno_backend.billing.gateway.razorpay.RazorpayRestClient;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway.BillPaymentGatewayException;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway.PayoutCommand;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway.PayoutLookup;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway.PayoutResult;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway.RazorpayPayoutGateway;

/** The adapter against a recording HTTP layer: what it posts, and how it reads what comes back. */
class RazorpayPayoutGatewayTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID PAYMENT = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final RecordingClient client = new RecordingClient();
    private final RazorpayPayoutGateway gateway = new RazorpayPayoutGateway(client, "2323230000000000");

    @Test
    void sendCreatesTheContactAndFundAccountAndPaysWithThePaymentIdAsTheKey() {
        client.answer("/contacts", "{\"id\":\"cont_1\"}");
        client.answer("/fund_accounts", "{\"id\":\"fa_1\"}");
        client.answer("/payouts", "{\"id\":\"pout_1\",\"status\":\"processing\",\"amount\":25000050}");

        PayoutResult result = gateway.send(command("250000.50"));

        assertThat(result.outcome()).isEqualTo(PayoutResult.Outcome.PROCESSING);
        assertThat(result.providerRef()).isEqualTo("pout_1");
        assertThat(result.amountPaise()).isEqualTo(25000050L);

        assertThat(client.calls).extracting(c -> c.path).containsExactly("/contacts", "/fund_accounts", "/payouts");
        Map<String, Object> contact = client.calls.get(0).body;
        assertThat(contact).containsEntry("name", "Sree Builders").containsEntry("type", "vendor")
                .containsEntry("email", "accounts@example.com").doesNotContainKey("contact");
        Map<String, Object> fund = client.calls.get(1).body;
        assertThat(fund).containsEntry("contact_id", "cont_1").containsEntry("account_type", "bank_account");
        @SuppressWarnings("unchecked")
        Map<String, Object> bank = (Map<String, Object>) fund.get("bank_account");
        assertThat(bank).containsEntry("account_number", "0123456789").containsEntry("ifsc", "FDRL0001234");
        Call payout = client.calls.get(2);
        assertThat(payout.body).containsEntry("account_number", "2323230000000000")
                .containsEntry("fund_account_id", "fa_1").containsEntry("amount", 25000050L)
                .containsEntry("currency", "INR").containsEntry("mode", "RTGS")
                .containsEntry("reference_id", PAYMENT.toString()).containsEntry("queue_if_low_balance", true);
        assertThat(payout.headers).containsEntry("X-Payout-Idempotency", PAYMENT.toString());
    }

    @Test
    void belowTwoLakhGoesByNeftAndTheNarrationIsCleanedAndCut() {
        client.answer("/contacts", "{\"id\":\"cont_1\"}");
        client.answer("/fund_accounts", "{\"id\":\"fa_1\"}");
        client.answer("/payouts", "{\"id\":\"pout_1\",\"status\":\"queued\"}");

        gateway.send(command("199999.99"));

        assertThat(client.calls.get(2).body).containsEntry("mode", "NEFT").containsEntry("amount", 19999999L)
                .containsEntry("narration", "SC 12 RA 01");
        assertThat(client.calls.get(2).body.get("narration").toString()).hasSizeLessThanOrEqualTo(30);
    }

    @Test
    void aProviderFailureBecomesTheRetryableExceptionAndKeepsItsTextOutOfTheMessage() {
        client.fail("/contacts", new BillingGatewayException("Razorpay POST /contacts failed: account 2323 is suspended"));

        assertThatThrownBy(() -> gateway.send(command("100.00")))
                .isInstanceOf(BillPaymentGatewayException.class)
                .hasMessageNotContaining("2323")
                .hasMessageContaining("no usable answer")
                .hasCauseInstanceOf(BillingGatewayException.class);
    }

    @Test
    void anAnswerWithNoIdIsNotUsable() {
        client.answer("/contacts", "{}");
        assertThatThrownBy(() -> gateway.send(command("100.00"))).isInstanceOf(BillPaymentGatewayException.class);
    }

    @Test
    void fetchReadsEachStatusAsAnOutcome() {
        assertThat(fetched("processed", "\"utr\":\"UTR9\"").outcome()).isEqualTo(PayoutResult.Outcome.SETTLED);
        assertThat(fetched("processed", "\"utr\":\"UTR9\"").bankReference()).isEqualTo("UTR9");
        assertThat(fetched("reversed", "\"failure_reason\":\"Account closed\"").outcome()).isEqualTo(PayoutResult.Outcome.REVERSED);
        assertThat(fetched("reversed", "\"failure_reason\":\"Account closed\"").failureReason()).isEqualTo("Account closed");
        assertThat(fetched("rejected", "\"status_details\":{\"description\":\"Bad IFSC\"}").failureReason()).isEqualTo("Bad IFSC");
        for (String failed : List.of("failed", "rejected", "cancelled")) {
            assertThat(fetched(failed, "\"x\":1").outcome()).as(failed).isEqualTo(PayoutResult.Outcome.FAILED);
        }
        for (String pending : List.of("queued", "pending", "processing", "something_new")) {
            assertThat(fetched(pending, "\"x\":1").outcome()).as(pending).isEqualTo(PayoutResult.Outcome.PROCESSING);
        }
    }

    @Test
    void fetchWithNoPayoutIdYetAsksForASend() {
        assertThatThrownBy(() -> gateway.fetch(new PayoutLookup(PAYMENT, null, BigDecimal.ONE)))
                .isInstanceOf(BillPaymentGatewayException.class);
    }

    @Test
    void theCommandDoesNotPrintTheAccount() {
        assertThat(command("1.00").toString()).doesNotContain("0123456789");
    }

    private PayoutResult fetched(String status, String extra) {
        client.answer("/payouts/pout_1", "{\"id\":\"pout_1\",\"status\":\"" + status + "\"," + extra + "}");
        return gateway.fetch(new PayoutLookup(PAYMENT, "pout_1", BigDecimal.ONE));
    }

    private static PayoutCommand command(String amount) {
        return new PayoutCommand(PAYMENT, new BigDecimal(amount), "SC-12 RA-01", "Sree Builders", "accounts@example.com", " ",
                "0123456789", "FDRL0001234");
    }

    private record Call(String path, Map<String, Object> body, Map<String, String> headers) {}

    /** Stands in for the HTTP layer, answering by path. */
    private static final class RecordingClient extends RazorpayRestClient {
        private final List<Call> calls = new ArrayList<>();
        private final Map<String, String> answers = new java.util.HashMap<>();
        private final Map<String, RuntimeException> failures = new java.util.HashMap<>();

        RecordingClient() {
            super(properties());
        }

        private static BillingGatewayProperties.Razorpay properties() {
            return new BillingGatewayProperties.Razorpay();
        }

        void answer(String path, String json) {
            answers.put(path, json);
        }

        void fail(String path, RuntimeException failure) {
            failures.put(path, failure);
        }

        @Override
        public JsonNode post(String path, Map<String, Object> body) {
            return post(path, body, Map.of());
        }

        @Override
        public JsonNode post(String path, Map<String, Object> body, Map<String, String> headers) {
            calls.add(new Call(path, body, headers));
            return reply(path);
        }

        @Override
        public JsonNode get(String path) {
            return reply(path);
        }

        private JsonNode reply(String path) {
            if (failures.containsKey(path)) {
                throw failures.get(path);
            }
            try {
                return JSON.readTree(answers.get(path));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
