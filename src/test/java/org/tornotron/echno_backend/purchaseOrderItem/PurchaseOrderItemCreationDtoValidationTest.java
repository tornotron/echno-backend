package org.tornotron.echno_backend.purchaseOrderItem;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.purchaseOrderItem.dto.PurchaseOrderItemCreationDto;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean validation on a purchase order line.
 *
 * <p>An unpriced line used to be accepted and then coerced to zero, so an order raised from a
 * form whose price fields were never filled in saved with a total of nothing and looked, on the
 * screen, like a rendering fault. The price is now required in its own right: a line genuinely
 * supplied free of charge sends a zero, which is a decision someone made rather than a field
 * nobody touched.
 */
class PurchaseOrderItemCreationDtoValidationTest {

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

    private PurchaseOrderItemCreationDto line(BigDecimal unitPrice) {
        PurchaseOrderItemCreationDto dto = new PurchaseOrderItemCreationDto();
        dto.setMaterialId(11L);
        dto.setOrderedQuantity(5);
        dto.setUnitPrice(unitPrice);
        return dto;
    }

    @Test
    void missingUnitPrice_isRejected() {
        assertThat(validator.validate(line(null)))
                .singleElement()
                .satisfies(violation -> {
                    assertThat(violation.getPropertyPath()).hasToString("unitPrice");
                    assertThat(violation.getMessage()).isEqualTo("Unit price is required");
                });
    }

    @Test
    void negativeUnitPrice_isRejected() {
        assertThat(validator.validate(line(new BigDecimal("-1.00"))))
                .singleElement()
                .satisfies(violation ->
                        assertThat(violation.getPropertyPath()).hasToString("unitPrice"));
    }

    @Test
    void zeroUnitPrice_isAllowedBecauseSendingItIsADecision() {
        assertThat(validator.validate(line(BigDecimal.ZERO))).isEmpty();
    }

    @Test
    void aPricedLine_passes() {
        assertThat(validator.validate(line(new BigDecimal("62.50")))).isEmpty();
    }
}
