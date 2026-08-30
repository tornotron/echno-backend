package org.tornotron.echno_backend.payable;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.payable.dto.PayableCreationDto;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean validation on the amounts a payable is created with.
 *
 * <p>Both figures used to carry no constraint beyond the recorded amount being present, so a
 * payable could be raised for zero, or raised already paid past what it recorded. Neither can be
 * corrected afterwards: the module has no update or delete endpoint, and recordPayment refuses
 * any payment that would push the paid total past the recorded one, so a payable in either state
 * is stuck there. The rules are checked here at the edge and again in the service.
 */
class PayableCreationDtoValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void openValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    private PayableCreationDto payable(BigDecimal amountRecorded, BigDecimal amountPaid) {
        PayableCreationDto dto = new PayableCreationDto();
        dto.setPayableNumber("PAY-2026-000001");
        dto.setContractorName("ACME Contractors");
        dto.setContractType("MATERIAL_SUPPLY");
        dto.setAmountRecorded(amountRecorded);
        dto.setAmountPaid(amountPaid);
        dto.setProjectId(1L);
        dto.setCreatedBy(1L);
        return dto;
    }

    @Test
    void zeroAmountRecorded_isRejected() {
        assertThat(validator.validate(payable(BigDecimal.ZERO, null)))
                .singleElement()
                .satisfies(violation -> {
                    assertThat(violation.getPropertyPath()).hasToString("amountRecorded");
                    assertThat(violation.getMessage()).isEqualTo("amount recorded must be greater than zero");
                });
    }

    @Test
    void negativeAmountRecorded_isRejected() {
        assertThat(validator.validate(payable(new BigDecimal("-1000.00"), null)))
                .singleElement()
                .satisfies(violation ->
                        assertThat(violation.getPropertyPath()).hasToString("amountRecorded"));
    }

    @Test
    void negativeAmountPaid_isRejected() {
        assertThat(validator.validate(payable(new BigDecimal("1000.00"), new BigDecimal("-1.00"))))
                .singleElement()
                .satisfies(violation -> {
                    assertThat(violation.getPropertyPath()).hasToString("amountPaid");
                    assertThat(violation.getMessage()).isEqualTo("amount paid cannot be negative");
                });
    }

    @Test
    void absentAmountPaid_isAccepted() {
        assertThat(validator.validate(payable(new BigDecimal("1000.00"), null))).isEmpty();
    }

    @Test
    void openingAmountPaidWithinTheRecordedAmount_isAccepted() {
        assertThat(validator.validate(payable(new BigDecimal("1000.00"), new BigDecimal("250.00")))).isEmpty();
    }
}
