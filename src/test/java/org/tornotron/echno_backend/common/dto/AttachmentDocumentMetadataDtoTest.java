package org.tornotron.echno_backend.common.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one constraint on the document metadata payload that no single field can carry: a document
 * cannot stop being valid before it was issued. Run against a bare Jakarta validator rather than
 * through the web layer, so it costs no Spring context.
 */
class AttachmentDocumentMetadataDtoTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void startValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void stopValidator() {
        factory.close();
    }

    private AttachmentDocumentMetadataDto dto(LocalDate issuedOn, LocalDate expiresOn) {
        AttachmentDocumentMetadataDto dto = new AttachmentDocumentMetadataDto();
        dto.setDocumentType("insurance");
        dto.setIssuedOn(issuedOn);
        dto.setExpiresOn(expiresOn);
        return dto;
    }

    @Test
    void anExpiryBeforeTheIssueDateIsRefused() {
        assertThat(validator.validate(dto(LocalDate.of(2026, 6, 10), LocalDate.of(2026, 1, 1))))
                .extracting(v -> v.getMessage())
                .containsExactly("expiresOn must not be before issuedOn");
    }

    @Test
    void anExpiryOnTheIssueDateIsAllowed() {
        assertThat(validator.validate(dto(LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 10)))).isEmpty();
    }

    @Test
    void anExpiryAfterTheIssueDateIsAllowed() {
        assertThat(validator.validate(dto(LocalDate.of(2026, 6, 10), LocalDate.of(2027, 6, 10)))).isEmpty();
    }

    @Test
    void aDocumentWithOnlyOneOfTheTwoDatesIsAllowed() {
        assertThat(validator.validate(dto(null, LocalDate.of(2027, 6, 10)))).isEmpty();
        assertThat(validator.validate(dto(LocalDate.of(2026, 6, 10), null))).isEmpty();
        assertThat(validator.validate(dto(null, null))).isEmpty();
    }
}
