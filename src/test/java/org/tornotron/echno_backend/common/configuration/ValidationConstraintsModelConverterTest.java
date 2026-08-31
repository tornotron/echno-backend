package org.tornotron.echno_backend.common.configuration;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Negative;
import jakarta.validation.constraints.NegativeOrZero;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the document says about a constrained field, resolved through the real swagger-core
 * chain rather than against a hand-built schema.
 *
 * <p>Going through {@link ModelConverters} is the point of these cases. The behaviour being
 * fixed is an ordering one inside {@code ModelResolver}: {@code @Size} assigns
 * {@code minLength} after {@code @NotBlank} has set it, so a test that built the schema itself
 * would not reproduce the thing that was wrong.
 */
class ValidationConstraintsModelConverterTest {

    /** A three-oh document, to check the version-dependent spelling of an exclusive bound. */
    private static final boolean OPENAPI_30 = false;

    @Test
    void aNotBlankFieldIsRequired() {
        assertThat(resolve(Request.class).getRequired()).contains("reason");
    }

    @Test
    void aNotBlankFieldSizedFromAboveStillCarriesALowerBound() {
        Schema<?> reason = property(resolve(Request.class), "reason");

        assertThat(reason.getMaxLength())
                .as("the upper bound swagger-core already wrote is left as it stands")
                .isEqualTo(1000);
        assertThat(reason.getMinLength())
                .as("@Size(max) defaults min to 0 and assigns it after @NotBlank, so without "
                        + "the converter the document permits the empty string the endpoint "
                        + "refuses")
                .isEqualTo(1);
    }

    @Test
    void aNotEmptyCollectionIsRequiredAndCannotBeEmpty() {
        Schema<?> model = resolve(Request.class);

        assertThat(model.getRequired()).contains("items");
        assertThat(property(model, "items").getMinItems()).isEqualTo(1);
    }

    @Test
    void aNotEmptyMapCannotBeEmpty() {
        assertThat(property(resolve(Request.class), "labels").getMinProperties()).isEqualTo(1);
    }

    @Test
    void aPositiveAmountIsBoundedAboveZeroRatherThanAtIt() {
        Schema<?> amount = property(resolve(Request.class), "amount");

        assertThat(amount.getExclusiveMinimumValue())
                .as("zero is not a positive amount, and minimum: 0 would say it is")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(amount.getMinimum()).isNull();
    }

    @Test
    void aPositiveOrZeroAmountIsBoundedAtZero() {
        Schema<?> paid = property(resolve(Request.class), "paid");

        assertThat(paid.getMinimum()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(paid.getExclusiveMinimumValue()).isNull();
    }

    @Test
    void aNegativeAmountIsBoundedBelowZero() {
        Schema<?> model = resolve(Request.class);

        assertThat(property(model, "correction").getExclusiveMaximumValue())
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(property(model, "drawdown").getMaximum())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void anEmailFieldCarriesTheEmailFormat() {
        assertThat(property(resolve(Request.class), "contact").getFormat()).isEqualTo("email");
    }

    @Test
    void aBoundAlreadyStatedIsNotOverwritten() {
        Schema<?> quantity = property(resolve(Request.class), "quantity");

        assertThat(quantity.getMinimum())
                .as("@Min said 5, and @Positive saying 0 would loosen it")
                .isEqualByComparingTo(BigDecimal.valueOf(5));
        assertThat(quantity.getExclusiveMinimumValue()).isNull();
    }

    @Test
    void aConstraintNamingValidationGroupsIsNotStated() {
        Schema<?> model = resolve(Request.class);

        assertThat(model.getRequired())
                .as("the constraint holds only for invocations asking for that group, and a "
                        + "schema keyword would hold for all of them")
                .doesNotContain("draftOnly");
        assertThat(property(model, "draftOnly").getMinLength()).isNull();
    }

    @Test
    void aFieldTheAuthorCalledOptionalIsLeftOptional() {
        assertThat(resolve(Request.class).getRequired()).doesNotContain("note");
    }

    @Test
    void anUnconstrainedFieldIsUntouched() {
        Schema<?> model = resolve(Request.class);

        assertThat(model.getRequired()).doesNotContain("comment");
        assertThat(property(model, "comment").getMinLength()).isNull();
    }

    @Test
    void aNotNullFieldKeepsTheRequiredEntrySwaggerCoreAlreadyWrote() {
        assertThat(resolve(Request.class).getRequired()).contains("projectId");
    }

    @Test
    void aRecordComponentIsReadTheSameWayAsAField() {
        Schema<?> model = resolve(RecordRequest.class);

        assertThat(model.getRequired()).contains("reason");
        assertThat(property(model, "reason").getMinLength()).isEqualTo(1);
    }

    @Test
    void anExclusiveBoundOnAThreeOhDocumentIsSpeltTheWayThreeOhSpellsIt() {
        Schema<?> amount = property(resolve(Request.class, OPENAPI_30), "amount");

        assertThat(amount.getExclusiveMinimum())
                .as("3.0 has no exclusiveMinimum value, only a flag beside an inclusive bound")
                .isTrue();
        assertThat(amount.getMinimum()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private static Schema<?> resolve(Class<?> type) {
        return resolve(type, true);
    }

    private static Schema<?> resolve(Class<?> type, boolean openapi31) {
        ModelConverters converters = new ModelConverters(openapi31);
        converters.addConverter(new ValidationConstraintsModelConverter());
        Map<String, Schema> models = converters.readAll(type);
        assertThat(models).containsKey(type.getSimpleName());
        return models.get(type.getSimpleName());
    }

    private static Schema<?> property(Schema<?> model, String name) {
        Schema<?> property = model.getProperties().get(name);
        assertThat(property).as("the model has no property %s", name).isNotNull();
        return property;
    }

    /** A validation group, named only so that a grouped constraint can be written below. */
    interface Draft {
    }

    /** One field per shape the converter has an opinion about, and several it must not touch. */
    @SuppressWarnings("unused")
    static class Request {

        @NotBlank
        @Size(max = 1000)
        public String reason;

        @NotEmpty
        public List<String> items;

        @NotEmpty
        public Map<String, String> labels;

        @NotNull
        @Positive
        public BigDecimal amount;

        @PositiveOrZero
        public BigDecimal paid;

        @Negative
        public BigDecimal correction;

        @NegativeOrZero
        public BigDecimal drawdown;

        @Email
        public String contact;

        @Positive
        @jakarta.validation.constraints.Min(5)
        public Integer quantity;

        @NotBlank(groups = Draft.class)
        public String draftOnly;

        @NotBlank
        @io.swagger.v3.oas.annotations.media.Schema(
                requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED)
        public String note;

        @NotNull
        public Long projectId;

        public String comment;
    }

    /** The same treatment on a record, which is how the newer request DTOs are written. */
    record RecordRequest(@NotBlank @Size(max = 1000) String reason) {
    }
}
