package org.tornotron.echno_backend.architecture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import io.swagger.v3.oas.annotations.media.Schema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every field marked {@code @Schema(nullable = true)} says so in the published document.
 *
 * <p>The annotation is not self-enforcing, and for the life of this document it did nothing at
 * all. springdoc serves OpenAPI 3.1, where nullability is a type union rather than a keyword, and
 * swagger-core reads {@code nullable} into the model and then drops it on the way out. So an
 * author could write the one annotation that means "this may be null", regenerate, see no diff,
 * and reasonably conclude the document had no way to say it. That is how a 3 MB contract came to
 * describe 3069 properties without marking a single one nullable, while a large share of them are
 * null on most rows. Issue #645.
 *
 * <p>{@link org.tornotron.echno_backend.common.configuration.NullableSchemaCustomizer} closes
 * that. This test is what keeps it closed: it reads the committed document and fails if any
 * annotated field is not actually described as admitting null. Without the customizer every
 * annotated field fails here, which is the point. A future springdoc or swagger-core that changes
 * how the union is written will fail here too, rather than quietly reverting the document to
 * saying nothing.
 *
 * <p>It reads {@code docs/openapi.json} rather than booting an application, because the committed
 * copy is already held to the served document by {@code OpenApiSnapshotTest} in its own task, and
 * a second Spring context is the one thing the test JVM has no room for.
 */
@AnalyzeClasses(
        packages = "org.tornotron.echno_backend",
        importOptions = ImportOption.DoNotIncludeTests.class)
class OpenApiNullabilityTest {

    /** The committed contract, relative to the project directory the test task runs in. */
    private static final Path DOCUMENT = Path.of("docs", "openapi.json");

    /** The JSON Schema type that admits only null. */
    private static final String NULL_TYPE = "null";

    /**
     * Reports every annotated field the document does not describe as nullable.
     *
     * @param productionClasses The imported production classes, supplied by ArchUnit.
     */
    @ArchTest
    static void everyNullableFieldIsDeclaredNullable(JavaClasses productionClasses) {
        JsonNode schemas = readSchemas();
        List<String> annotated = new ArrayList<>();
        List<String> undeclared = new ArrayList<>();

        for (JavaClass javaClass : productionClasses) {
            for (JavaField field : javaClass.getFields()) {
                if (!isMarkedNullable(field)) {
                    continue;
                }
                String property = javaClass.getSimpleName() + "." + field.getName();
                annotated.add(property);

                JsonNode declared = schemas.path(javaClass.getSimpleName())
                        .path("properties").path(field.getName());
                if (declared.isMissingNode()) {
                    undeclared.add(property + ": the document has no such property. A renamed "
                            + "field, or a schema springdoc names differently");
                } else if (!admitsNull(declared)) {
                    undeclared.add(property + ": " + declared);
                }
            }
        }

        assertThat(annotated)
                .as("no field carries @Schema(nullable = true), so this test is checking nothing. "
                        + "Either the annotation has been removed from the DTO layer or the class "
                        + "scan has stopped finding it")
                .isNotEmpty();
        assertThat(undeclared)
                .as("these fields are marked nullable in the code but the committed document does "
                        + "not say they admit null, so a client reading the contract will write no "
                        + "guard for them. Regenerate with ./gradlew openApiSnapshot "
                        + "-PupdateOpenApiSnapshot, and if the field is still not described as "
                        + "nullable check NullableSchemaCustomizer can express its shape")
                .isEmpty();
    }

    private static boolean isMarkedNullable(JavaField field) {
        return field.isAnnotatedWith(Schema.class)
                && field.getAnnotationOfType(Schema.class).nullable();
    }

    /**
     * Whether a property schema admits null: either as a member of its type union, or as a branch
     * of the {@code anyOf} a reference has to be wrapped in to say the same thing.
     */
    private static boolean admitsNull(JsonNode property) {
        JsonNode type = property.path("type");
        if (type.isArray()) {
            for (JsonNode member : type) {
                if (NULL_TYPE.equals(member.asText())) {
                    return true;
                }
            }
        }
        if (NULL_TYPE.equals(type.asText())) {
            return true;
        }
        for (String composition : List.of("anyOf", "oneOf")) {
            for (JsonNode branch : property.path(composition)) {
                if (admitsNull(branch)) {
                    return true;
                }
            }
        }
        return false;
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
