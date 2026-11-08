package org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway;

import java.math.BigDecimal;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.BillPaymentProvider;

/**
 * Stands in for a provider when testing the flow. Sending always reaches {@code PROCESSING}; asking
 * about it then settles it, or fails it for an amount ending in {@value #FAILING_PAISE}, so both
 * endings can be tried by choosing the amount. No money moves, and every payment it carries is labelled
 * as a test on the page and the timeline.
 */
public class FakeBillPaymentGateway implements BillPaymentGateway {

    static final String FAILING_PAISE = ".13";

    @Override
    public BillPaymentProvider provider() {
        return BillPaymentProvider.FAKE;
    }

    @Override
    public PayoutResult send(PayoutCommand command) {
        return new PayoutResult(PayoutResult.Outcome.PROCESSING, reference(command.paymentId().toString()), null, null,
                null);
    }

    @Override
    public PayoutResult fetch(PayoutLookup lookup) {
        String reference = lookup.providerRef();
        if (fails(lookup.amount())) {
            return new PayoutResult(PayoutResult.Outcome.FAILED, reference, null, "Test failure: the amount ends in "
                    + FAILING_PAISE, null);
        }
        return new PayoutResult(PayoutResult.Outcome.SETTLED, reference, "TEST-" + reference.substring(5, 17), null, null);
    }

    private static String reference(String paymentId) {
        return "fake_" + paymentId.replace("-", "");
    }

    private static boolean fails(BigDecimal amount) {
        return amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().endsWith(FAILING_PAISE);
    }
}
