package org.tornotron.echno_backend.architecture;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Negative;
import jakarta.validation.constraints.NegativeOrZero;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every validation constraint the endpoints enforce is stated in the published document.
 *
 * <p>swagger-core turns a jakarta constraint into a schema keyword in one place, and three
 * things were missing from it. {@code required} is written for {@code @NotNull} and nothing
 * else, so a {@code @NotBlank} field looked optional. {@code @Size} assigns {@code minLength}
 * unconditionally and runs after {@code @NotBlank}, so the common pairing
 * {@code @NotBlank @Size(max = n)} published {@code minLength: 0}, which is worse than saying
 * nothing: it positively permits the empty string the endpoint refuses. And
 * {@code @Positive}, {@code @PositiveOrZero}, {@code @Negative}, {@code @NegativeOrZero} and
 * {@code @Email} were not read at all. Issue #657.
 *
 * <p>{@link org.tornotron.echno_backend.common.configuration.ValidationConstraintsModelConverter}
 * closes that, and this is what keeps it closed. It reads the committed document and fails when
 * an annotated field is not described the way the annotation behind it behaves, so a springdoc
 * or swagger-core upgrade that changes the resolution order cannot quietly take the constraints
 * back out.
 *
 * <p>It checks only what the annotation itself guarantees. {@code @NotBlank} also rejects a
 * string of blanks and {@code minLength: 1} does not say so, so the document understates it on
 * purpose; a contract that overstates a constraint is the worse failure, because a client
 * pre-validating against it refuses input the server would have taken.
 *
 * <p>It reads {@code docs/openapi.json} rather than booting an application, because the
 * committed copy is already held to the served document by {@code OpenApiSnapshotTest} in its
 * own task, and a second Spring context is the one thing the test JVM has no room for.
 */
@AnalyzeClasses(
        packages = "org.tornotron.echno_backend",
        importOptions = ImportOption.DoNotIncludeTests.class)
class OpenApiValidationConstraintsTest {

    /** The committed contract, relative to the project directory the test task runs in. */
    private static final Path DOCUMENT = Path.of("docs", "openapi.json");

    /** The JSON Schema format a string carrying an email address is given. */
    private static final String EMAIL_FORMAT = "email";

    /**
     * Reports every annotated field the document describes more loosely than the endpoint
     * enforces, grouped by the four things that were wrong.
     *
     * @param productionClasses The imported production classes, supplied by ArchUnit.
     */
    @ArchTest
    static void everyConstrainedFieldIsDescribed(JavaClasses productionClasses) {
        JsonNode schemas = readSchemas();
        Map<String, List<String>> unstated = new LinkedHashMap<>();
        unstated.put("not in required, although the constraint rejects null", new ArrayList<>());
        unstated.put("no minimum length or item count, although the constraint rejects empty",
                new ArrayList<>());
        unstated.put("no numeric bound, although the constraint rejects the wrong sign",
                new ArrayList<>());
        unstated.put("no email format", new ArrayList<>());
        unstated.put("annotated on a published schema, but the document has no such property",
                new ArrayList<>());
        int checked = 0;

        for (JavaClass javaClass : productionClasses) {
            for (JavaField field : javaClass.getFields()) {
                boolean notBlank = carries(field, NotBlank.class);
                boolean notEmpty = carries(field, NotEmpty.class);
                boolean positive = carries(field, Positive.class);
                boolean positiveOrZero = carries(field, PositiveOrZero.class);
                boolean negative = carries(field, Negative.class);
                boolean negativeOrZero = carries(field, NegativeOrZero.class);
                boolean email = carries(field, Email.class);
                if (!(notBlank || notEmpty || positive || positiveOrZero
                        || negative || negativeOrZero || email)) {
                    continue;
                }
                String schemaName = schemaName(javaClass);
                JsonNode schema = schemas.path(schemaName);
                if (schema.isMissingNode()) {
                    // The class is annotated but not published: an entity, or a type no endpoint
                    // exposes. The document says nothing about it because it says nothing about
                    // the class, which is not this test's business.
                    continue;
                }
                checked++;

                String name = propertyName(field);
                String where = schemaName + "." + name;
                JsonNode property = schema.path("properties").path(name);
                if (property.isMissingNode()) {
                    unstated.get("annotated on a published schema, but the document has no such "
                            + "property").add(where);
                    continue;
                }

                if ((notBlank || notEmpty) && !isRequired(schema, name)) {
                    unstated.get("not in required, although the constraint rejects null")
                            .add(where);
                }
                if ((notBlank || notEmpty) && !hasFloor(property)) {
                    unstated.get("no minimum length or item count, although the constraint "
                            + "rejects empty").add(where + ": " + property);
                }
                if ((positive || positiveOrZero) && !hasLowerBound(property, positive)) {
                    unstated.get("no numeric bound, although the constraint rejects the wrong "
                            + "sign").add(where + ": " + property);
                }
                if ((negative || negativeOrZero) && !hasUpperBound(property, negative)) {
                    unstated.get("no numeric bound, although the constraint rejects the wrong "
                            + "sign").add(where + ": " + property);
                }
                if (email && !EMAIL_FORMAT.equals(property.path("format").asText(null))) {
                    unstated.get("no email format").add(where + ": " + property);
                }
            }
        }

        assertThat(checked)
                .as("no field carries a validation constraint this test knows how to check, so "
                        + "it is checking nothing. Either the constraints have been removed from "
                        + "the DTO layer or the class scan has stopped finding them")
                .isPositive();
        assertThat(flatten(unstated))
                .as("these fields are constrained in the code but the committed document does not "
                        + "say so, so a client reading the contract will send what the endpoint "
                        + "refuses and a contract check reading it cannot tell. Regenerate with "
                        + "./gradlew openApiSnapshot -PupdateOpenApiSnapshot, and if a field is "
                        + "still not described check ValidationConstraintsModelConverter can "
                        + "express its shape")
                .isEmpty();
    }

    /**
     * Whether the field carries the constraint in a form that holds for every request. A
     * constraint naming validation groups applies only to the invocations that ask for those
     * groups, and a schema keyword applies to all of them, so the document leaves it out and so
     * does this check.
     */
    private static boolean carries(JavaField field, Class<? extends Annotation> constraint) {
        if (!field.isAnnotatedWith(constraint)) {
            return false;
        }
        Annotation present = field.getAnnotationOfType(constraint);
        try {
            Class<?>[] groups = (Class<?>[]) constraint.getMethod("groups").invoke(present);
            return groups.length == 0;
        } catch (ReflectiveOperationException | ClassCastException e) {
            return false;
        }
    }

    /**
     * The name the class is published under. Usually the simple name, unless {@code @Schema}
     * gives it one of its own, which is how a nested type whose simple name another nested type
     * also carries is kept apart from it.
     */
    private static String schemaName(JavaClass javaClass) {
        if (javaClass.isAnnotatedWith(io.swagger.v3.oas.annotations.media.Schema.class)) {
            String named = javaClass
                    .getAnnotationOfType(io.swagger.v3.oas.annotations.media.Schema.class).name();
            if (!named.isBlank()) {
                return named;
            }
        }
        return javaClass.getSimpleName();
    }

    /** The name the property carries in the document, which Jackson may have renamed. */
    private static String propertyName(JavaField field) {
        if (field.isAnnotatedWith(JsonProperty.class)) {
            String renamed = field.getAnnotationOfType(JsonProperty.class).value();
            if (!renamed.isEmpty() && !JsonProperty.USE_DEFAULT_NAME.equals(renamed)) {
                return renamed;
            }
        }
        return field.getName();
    }

    private static boolean isRequired(JsonNode schema, String name) {
        for (JsonNode required : schema.path("required")) {
            if (name.equals(required.asText())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the property states a floor that rules out the empty value, whichever of the three
     * shapes a "not empty" constraint can land on.
     */
    private static boolean hasFloor(JsonNode property) {
        for (String keyword : List.of("minLength", "minItems", "minProperties")) {
            if (property.path(keyword).asInt(0) >= 1) {
                return true;
            }
        }
        // A property that is a reference to another model carries no length of its own, and
        // required already says it cannot be left out. Nothing further to state.
        return property.has("$ref") || property.has("allOf");
    }

    private static boolean hasLowerBound(JsonNode property, boolean strict) {
        if (property.has("exclusiveMinimum") && property.path("exclusiveMinimum").asDouble() >= 0) {
            return true;
        }
        if (!property.has("minimum")) {
            return false;
        }
        double minimum = property.path("minimum").asDouble();
        return strict ? minimum > 0 : minimum >= 0;
    }

    private static boolean hasUpperBound(JsonNode property, boolean strict) {
        if (property.has("exclusiveMaximum") && property.path("exclusiveMaximum").asDouble() <= 0) {
            return true;
        }
        if (!property.has("maximum")) {
            return false;
        }
        double maximum = property.path("maximum").asDouble();
        return strict ? maximum < 0 : maximum <= 0;
    }

    /** The four groups as one list, each entry carrying the heading it was filed under. */
    private static List<String> flatten(Map<String, List<String>> unstated) {
        List<String> all = new ArrayList<>();
        unstated.forEach((heading, entries) -> {
            if (!entries.isEmpty()) {
                all.add(entries.size() + " " + heading + ":");
                all.addAll(entries);
            }
        });
        return all;
    }

    private static JsonNode readSchemas() {
        assertThat(DOCUMENT)
                .as("the committed OpenAPI document is missing; run ./gradlew openApiSnapshot "
                        + "-PupdateOpenApiSnapshot")
                .exists();
        try {
            return new ObjectMapper().readTree(Files.readString(DOCUMENT))
                    .path("components").path("schemas");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + DOCUMENT.toAbsolutePath(), e);
        }
    }
}
