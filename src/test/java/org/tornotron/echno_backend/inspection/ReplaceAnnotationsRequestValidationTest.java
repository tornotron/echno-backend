package org.tornotron.echno_backend.inspection;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.inspection.dtos.DefectPhotoAnnotationRequest;
import org.tornotron.echno_backend.inspection.dtos.ReplaceAnnotationsRequest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the annotation payload refuses before it reaches the service.
 *
 * <p>The null entry is the one worth a test of its own. Cascading validation
 * skips a null container element rather than failing it, so a list marked only
 * {@code @Valid} lets {@code {"annotations": [null]}} through and the service
 * dereferences it, turning a bad request into a 500. The {@code @NotNull} on the
 * element is what closes that, and nothing else in the suite would notice if it
 * were removed.
 *
 * <p>Runs the real validator directly rather than through MockMvc: the assertion
 * is about the constraints on the record, and a web slice would cost the 1 GB
 * test JVM another cached context.
 */
class ReplaceAnnotationsRequestValidationTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @Test
    void refusesANullEntryInTheList() {
        ReplaceAnnotationsRequest req =
                new ReplaceAnnotationsRequest(Collections.singletonList(null));

        assertThat(VALIDATOR.validate(req)).isNotEmpty();
    }

    @Test
    void refusesAnAbsentList() {
        assertThat(VALIDATOR.validate(new ReplaceAnnotationsRequest(null))).isNotEmpty();
    }

    @Test
    void refusesMoreMarksThanOneInspectionMayCarry() {
        List<DefectPhotoAnnotationRequest> tooMany =
                new ArrayList<>(ReplaceAnnotationsRequest.MAX_ANNOTATIONS + 1);
        for (int i = 0; i <= ReplaceAnnotationsRequest.MAX_ANNOTATIONS; i++) {
            tooMany.add(valid());
        }

        assertThat(VALIDATOR.validate(new ReplaceAnnotationsRequest(tooMany))).isNotEmpty();
    }

    @Test
    void refusesACoordinateOffTheImage() {
        DefectPhotoAnnotationRequest offImage = new DefectPhotoAnnotationRequest(
                "photo.jpg", DefectAnnotationShape.RECTANGLE,
                new BigDecimal("0.1"), new BigDecimal("0.1"),
                new BigDecimal("1.4"), new BigDecimal("0.5"), "Off the plate");

        assertThat(VALIDATOR.validate(new ReplaceAnnotationsRequest(List.of(offImage))))
                .isNotEmpty();
    }

    @Test
    void acceptsAWellFormedSet() {
        assertThat(VALIDATOR.validate(new ReplaceAnnotationsRequest(List.of(valid())))).isEmpty();
    }

    @Test
    void acceptsAnEmptySet() {
        assertThat(VALIDATOR.validate(new ReplaceAnnotationsRequest(List.of()))).isEmpty();
    }

    private static DefectPhotoAnnotationRequest valid() {
        return new DefectPhotoAnnotationRequest("photo.jpg", DefectAnnotationShape.RECTANGLE,
                new BigDecimal("0.10"), new BigDecimal("0.20"),
                new BigDecimal("0.40"), new BigDecimal("0.55"), "Honeycombing");
    }
}
