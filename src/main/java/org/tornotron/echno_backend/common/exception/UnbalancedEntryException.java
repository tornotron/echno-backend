package org.tornotron.echno_backend.common.exception;

import java.math.BigDecimal;

public class UnbalancedEntryException extends RuntimeException {
    public UnbalancedEntryException(BigDecimal totalDebit, BigDecimal totalCredit) {
        super("Journal entry is unbalanced: debits=" + totalDebit + ", credits=" + totalCredit);
    }
}
