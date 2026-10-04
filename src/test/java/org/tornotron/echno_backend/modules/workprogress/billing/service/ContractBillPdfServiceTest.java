package org.tornotron.echno_backend.modules.workprogress.billing.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Rupees on the printed bill are grouped the Indian way: thousands, then pairs. */
class ContractBillPdfServiceTest {

    @Test
    void rupeesUseIndianGrouping() {
        assertThat(ContractBillPdfService.inr(new BigDecimal("4250000"))).isEqualTo("Rs. 42,50,000.00");
        assertThat(ContractBillPdfService.inr(new BigDecimal("2199933.735"))).isEqualTo("Rs. 21,99,933.74");
        assertThat(ContractBillPdfService.inr(new BigDecimal("999.5"))).isEqualTo("Rs. 999.50");
        assertThat(ContractBillPdfService.inr(new BigDecimal("1000"))).isEqualTo("Rs. 1,000.00");
        assertThat(ContractBillPdfService.inr(new BigDecimal("123456789012"))).isEqualTo("Rs. 1,23,45,67,89,012.00");
        assertThat(ContractBillPdfService.inr(new BigDecimal("-150000"))).isEqualTo("Rs. -1,50,000.00");
    }
}
