package org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway;

/** The provider gave no usable answer. Its text goes in the log and on the payment, not in a response body. */
public class BillPaymentGatewayException extends RuntimeException {

    public BillPaymentGatewayException(String message) {
        super(message);
    }

    public BillPaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
