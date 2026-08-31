package org.tornotron.echno_backend.common.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JavaType;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Negative;
import jakarta.validation.constraints.NegativeOrZero;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Writes into the document the validation constraints the endpoints already enforce and
 * swagger-core does not carry across.
 *
 * <h2>What was wrong</h2>
 *
 * <p>The published contract described far less than the code refuses. Three separate gaps, all
 * of them in {@code ModelResolver.applyBeanValidatorAnnotations}, which is the one place
 * swagger-core 2.2.29 turns a jakarta constraint into a schema keyword:
 *
 * <ul>
 *   <li><b>{@code required} is written for {@code @NotNull} and nothing else.</b> The list it
 *       consults is only reached through the {@code @NotNull} branch, so {@code @NotBlank} and
 *       {@code @NotEmpty} never put a property in {@code required}, although both reject null
 *       exactly as {@code @NotNull} does. A reader was told the field could be left out
 *       entirely.
 *   <li><b>{@code @Size} overwrites the {@code minLength} that {@code @NotBlank} sets.</b>
 *       {@code @NotBlank} does produce {@code minLength: 1}; the {@code @Size} branch runs after
 *       it and assigns {@code Size.min()} unconditionally, which defaults to 0. So the common
 *       pairing {@code @NotBlank @Size(max = 1000)} publishes {@code minLength: 0}, which is
 *       worse than silence: it positively permits the empty string the endpoint refuses.
 *   <li><b>{@code @Positive}, {@code @PositiveOrZero}, {@code @Negative},
 *       {@code @NegativeOrZero} and {@code @Email} are not handled at all,</b> so a field that
 *       must be a positive amount carried no {@code minimum} and an address field carried no
 *       {@code format}.
 * </ul>
 *
 * <p>The consequence is not academic. {@code echno-core} reduces this document and checks every
 * outgoing write call against it, and it reads {@code required} to decide whether a caller may
 * omit a field; with only the {@code @NotNull} fields listed it passes a request the server will
 * refuse. On the other side {@code echno-web} has hardcoded a length cap of its own to stand in
 * for a bound the document never published. See issue #657.
 *
 * <h2>Why this is a model converter and not a document customizer</h2>
 *
 * <p>{@link NullableSchemaCustomizer} could work on the assembled document because the flag it
 * reads survives resolution. These constraints do not: they are consumed as each property is
 * resolved, and {@code required} is written on the parent at that same moment. By the time a
 * customizer runs, a {@code maxLength: 255} with no {@code minLength} beside it is
 * indistinguishable from a plain {@code @Size}, and a missing name in {@code required} is
 * indistinguishable from a field that is genuinely optional. Only something holding the
 * annotations can tell those apart, which is what this does: it delegates down the converter
 * chain, then re-reads the class the resolved schema was built from and repairs the properties
 * against the annotations on its fields.
 *
 * <p>Running after the chain rather than before it is deliberate. swagger-core applies
 * {@code @Size} after {@code @NotBlank}, so a converter that wrote {@code minLength: 1} on the
 * way in would have it overwritten on the way out.
 *
 * <h2>What it will not say</h2>
 *
 * <p>A document that overstates a constraint is worse than one that omits it, because a client
 * will pre-validate against it and refuse input the server would have accepted. So this writes
 * only what the annotation itself guarantees, and leaves the rest alone:
 *
 * <ul>
 *   <li>{@code @NotBlank} rejects a string of blanks, and {@code minLength: 1} does not. The
 *       document therefore understates it. Expressing the rest needs a {@code pattern}, which
 *       would collide with a {@code @Pattern} already on the field.
 *   <li>a constraint declaring validation {@code groups} is skipped, because whether it applies
 *       depends on the group the invocation asks for and the document has no way to say
 *       "sometimes".
 *   <li>{@code @Min}, {@code @Max}, {@code @DecimalMin}, {@code @DecimalMax}, {@code @Size} and
 *       {@code @Pattern} are left to swagger-core, which already writes them correctly.
 *   <li>{@code @Past}, {@code @Future} and {@code @Digits} have no keyword that means what they
 *       mean, and cross-field or custom class-level constraints have none either.
 *   <li>constraints on controller method parameters go through springdoc's parameter builder
 *       rather than this resolver, so a {@code @NotBlank @RequestParam} is untouched.
 * </ul>
 */
@Component
@Slf4j
public class ValidationConstraintsModelConverter implements ModelConverter {

    /** The lowest length or item count a "must not be empty" constraint allows. */
    private static final int NOT_EMPTY_MINIMUM = 1;

    /** The JSON Schema format for a string that has to be an email address. */
    private static final String EMAIL_FORMAT = "email";

    @Override
    @SuppressWarnings("rawtypes")
    public Schema resolve(AnnotatedType type, ModelConverterContext context,
                          Iterator<ModelConverter> chain) {
        if (!chain.hasNext()) {
            return null;
        }
        Schema resolved = chain.next().resolve(type, context, chain);
        Class<?> beanClass = rawClass(type == null ? null : type.getType());
        if (beanClass == null) {
            return resolved;
        }
        Schema<?> model = modelBehind(resolved, context);
        if (model != null && model.getProperties() != null) {
            describe(beanClass, model);
        }
        return resolved;
    }

    /**
     * The schema carrying the properties. A model that is already defined comes back from the
     * chain as a bare {@code $ref}, and the object the reference points at is the one holding
     * the properties to repair.
     */
    private static Schema<?> modelBehind(Schema<?> resolved, ModelConverterContext context) {
        if (resolved == null) {
            return null;
        }
        String ref = resolved.get$ref();
        if (ref == null) {
            return resolved;
        }
        if (!ref.startsWith(Components.COMPONENTS_SCHEMAS_REF)) {
            return null;
        }
        Map<String, Schema> defined = context.getDefinedModels();
        return defined == null
                ? null
                : defined.get(ref.substring(Components.COMPONENTS_SCHEMAS_REF.length()));
    }

    /** The class a resolved type was built from, whatever shape the type arrived in. */
    private static Class<?> rawClass(Type type) {
        if (type instanceof JavaType javaType) {
            return javaType.getRawClass();
        }
        if (type instanceof Class<?> raw) {
            return raw;
        }
        if (type instanceof ParameterizedType parameterized) {
            return rawClass(parameterized.getRawType());
        }
        return null;
    }

    /**
     * Repairs every property of one model against the constraints on the field behind it.
     * Idempotent: a model reached twice is described once and then left as it stands, since
     * every write here either raises a bound to a value it already holds or is a no-op.
     */
    private void describe(Class<?> beanClass, Schema<?> model) {
        for (Class<?> declaring = beanClass;
             declaring != null && declaring != Object.class;
             declaring = declaring.getSuperclass()) {
            for (Field field : declaring.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                String name = propertyName(field);
                Schema<?> property = model.getProperties().get(name);
                if (property != null) {
                    describe(field, name, property, model);
                }
            }
        }
    }

    /** The name the property carries in the document, which Jackson may have renamed. */
    private static String propertyName(Field field) {
        JsonProperty renamed = field.getAnnotation(JsonProperty.class);
        if (renamed != null && !renamed.value().isEmpty()
                && !JsonProperty.USE_DEFAULT_NAME.equals(renamed.value())) {
            return renamed.value();
        }
        return field.getName();
    }

    private void describe(Field field, String name, Schema<?> property, Schema<?> model) {
        Set<String> types = typesOf(property);
        boolean rejectsNull = false;

        if (applies(field.getAnnotation(NotBlank.class))) {
            rejectsNull = true;
            if (types.contains("string")) {
                raiseMinLength(property);
            }
        }
        if (applies(field.getAnnotation(NotEmpty.class))) {
            rejectsNull = true;
            if (types.contains("string")) {
                raiseMinLength(property);
            }
            if (types.contains("array") && below(property.getMinItems())) {
                property.setMinItems(NOT_EMPTY_MINIMUM);
            }
            if (types.contains("object") && below(property.getMinProperties())) {
                property.setMinProperties(NOT_EMPTY_MINIMUM);
            }
        }
        if (rejectsNull) {
            require(model, name, field);
        }
        if (isNumber(types) && !hasLowerBound(property)) {
            if (applies(field.getAnnotation(Positive.class))) {
                // An exclusive bound rather than minimum 1, which would be right for an integer
                // and wrong for the BigDecimal amounts most of these are.
                setExclusiveMinimum(property);
            } else if (applies(field.getAnnotation(PositiveOrZero.class))) {
                property.setMinimum(BigDecimal.ZERO);
            }
        }
        if (isNumber(types) && !hasUpperBound(property)) {
            if (applies(field.getAnnotation(Negative.class))) {
                setExclusiveMaximum(property);
            } else if (applies(field.getAnnotation(NegativeOrZero.class))) {
                property.setMaximum(BigDecimal.ZERO);
            }
        }
        if (applies(field.getAnnotation(Email.class))
                && types.contains("string") && property.getFormat() == null) {
            property.setFormat(EMAIL_FORMAT);
        }
    }

    /**
     * States that a value must be above zero. 3.1 spells an exclusive bound as the bound itself,
     * {@code "exclusiveMinimum": 0}; 3.0 spells it as an inclusive bound with a flag beside it,
     * and swagger-core writes whichever of the two the document's version calls for while
     * ignoring the other. Setting the wrong one is not an error, it is silence.
     */
    private static void setExclusiveMinimum(Schema<?> property) {
        if (SpecVersion.V31.equals(property.getSpecVersion())) {
            property.setExclusiveMinimumValue(BigDecimal.ZERO);
        } else {
            property.setMinimum(BigDecimal.ZERO);
            property.setExclusiveMinimum(Boolean.TRUE);
        }
    }

    /** The upper-bound counterpart of {@link #setExclusiveMinimum}. */
    private static void setExclusiveMaximum(Schema<?> property) {
        if (SpecVersion.V31.equals(property.getSpecVersion())) {
            property.setExclusiveMaximumValue(BigDecimal.ZERO);
        } else {
            property.setMaximum(BigDecimal.ZERO);
            property.setExclusiveMaximum(Boolean.TRUE);
        }
    }

    /**
     * Whether a constraint is one the document can state. A constraint that names validation
     * groups holds only for the invocations that ask for those groups, and a schema keyword
     * holds for every request, so writing one from the other would put a claim in the contract
     * that the endpoint does not always enforce.
     */
    private static boolean applies(java.lang.annotation.Annotation constraint) {
        if (constraint == null) {
            return false;
        }
        try {
            Class<?>[] groups = (Class<?>[]) constraint.annotationType()
                    .getMethod("groups").invoke(constraint);
            return groups.length == 0;
        } catch (ReflectiveOperationException | ClassCastException e) {
            log.warn("Cannot read the validation groups of {}, so it is left out of the document",
                    constraint.annotationType().getName(), e);
            return false;
        }
    }

    /** The types a property admits, under 3.1 a set and under 3.0 a single value. */
    private static Set<String> typesOf(Schema<?> property) {
        if (property.getTypes() != null && !property.getTypes().isEmpty()) {
            return property.getTypes();
        }
        return property.getType() == null
                ? Collections.emptySet()
                : new LinkedHashSet<>(Collections.singletonList(property.getType()));
    }

    private static boolean isNumber(Set<String> types) {
        return types.contains("number") || types.contains("integer");
    }

    private static boolean hasLowerBound(Schema<?> property) {
        return property.getMinimum() != null || property.getExclusiveMinimumValue() != null;
    }

    private static boolean hasUpperBound(Schema<?> property) {
        return property.getMaximum() != null || property.getExclusiveMaximumValue() != null;
    }

    private static void raiseMinLength(Schema<?> property) {
        if (below(property.getMinLength())) {
            property.setMinLength(NOT_EMPTY_MINIMUM);
        }
    }

    /** Whether a bound is absent, or present but weaker than the constraint behind it. */
    private static boolean below(Integer bound) {
        return bound == null || bound < NOT_EMPTY_MINIMUM;
    }

    /**
     * Records that the property may not be left out. {@code addRequiredItem} keeps the list
     * sorted, so a name is added once and the order does not depend on field order.
     */
    private static void require(Schema<?> model, String name, Field field) {
        io.swagger.v3.oas.annotations.media.Schema declared =
                field.getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);
        if (declared != null && io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED
                .equals(declared.requiredMode())) {
            // The author has said the field is optional. Believe them over the constraint rather
            // than publishing a contradiction.
            return;
        }
        if (model.getRequired() == null || !model.getRequired().contains(name)) {
            model.addRequiredItem(name);
        }
    }
}
