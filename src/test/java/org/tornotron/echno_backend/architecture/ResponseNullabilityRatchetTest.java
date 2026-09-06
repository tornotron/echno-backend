package org.tornotron.echno_backend.architecture;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.AnnotatedField;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.architecture.ReviewedResponseSchemas.ReviewedSchema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * On a reviewed response schema, an unmarked property is a claim that the server never sends null
 * there.
 *
 * <p>{@code OpenApiNullabilityTest} checks one direction: a field marked
 * {@code @Schema(nullable = true)} says so in the published document. That is what keeps the #661
 * customizer working, and it is all it can do, because across the rest of the document an
 * unmarked property means nothing at all. 1987 response-only properties say nothing about null,
 * and a reader cannot tell the ones nobody has looked at from the ones that are genuinely never
 * null.
 *
 * <p>This test is the other direction, over the schemas in
 * {@link ReviewedResponseSchemas#schemas()} only. For those it requires the three places the
 * answer is written to agree: the table, the annotation on the field, and the type union in
 * {@code docs/openapi.json}. Two things follow that the one-directional test cannot give:
 *
 * <ul>
 *   <li>a property on a reviewed schema that admits null without being listed fails, so silence
 *       on a reviewed schema is a decision rather than an omission;
 *   <li>a property added to a reviewed schema fails until it is classified, because the table
 *       names the non-null half explicitly and the union of the two halves has to be the
 *       published property list exactly. A field that arrives with no thought given to it cannot
 *       drift into the non-null half by being the complement of the marked one.
 * </ul>
 *
 * <p>Reads the committed document rather than booting an application, as
 * {@code OpenApiNullabilityTest} does and for the same reason: {@code OpenApiSnapshotTest} already
 * holds that file to the document the code serves, in a task of its own.
 */
class ResponseNullabilityRatchetTest {

    /** The committed contract, relative to the project directory the test task runs in. */
    private static final Path DOCUMENT = Path.of("docs", "openapi.json");

    /** The JSON Schema type that admits only null. */
    private static final String NULL_TYPE = "null";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("a reviewed schema accounts for every property it publishes")
    void everyPublishedPropertyIsClassified() {
        JsonNode schemas = readSchemas();
        List<String> wrong = new ArrayList<>();

        for (ReviewedSchema reviewed : ReviewedResponseSchemas.schemas()) {
            Set<String> published = publishedProperties(schemas, reviewed);

            Set<String> both = new LinkedHashSet<>(reviewed.nullable());
            both.retainAll(reviewed.nonNull());
            if (!both.isEmpty()) {
                wrong.add(reviewed + ": " + both + " are listed as both nullable and non-null");
            }

            Set<String> unclassified = new TreeSet<>(published);
            unclassified.removeAll(reviewed.nullable());
            unclassified.removeAll(reviewed.nonNull());
            if (!unclassified.isEmpty()) {
                wrong.add(reviewed + ": " + unclassified + " are published but not classified. "
                        + "Read what the schema, the mapper or the query behind each one can "
                        + "produce, then add it to the nullable or the non-null half");
            }

            Set<String> stale = new TreeSet<>(reviewed.nullable());
            stale.addAll(reviewed.nonNull());
            stale.removeAll(published);
            if (!stale.isEmpty()) {
                wrong.add(reviewed + ": " + stale + " are classified but the document publishes "
                        + "no such property. A renamed or removed field");
            }
        }

        assertThat(wrong)
                .as("ReviewedResponseSchemas and the published document disagree about which "
                        + "properties exist. Until they agree, an unmarked property on these "
                        + "schemas cannot be read as a claim that it is never null")
                .isEmpty();
    }

    @Test
    @DisplayName("a reviewed property admits null in the document exactly when the table says so")
    void theDocumentAgreesWithTheTable() {
        JsonNode schemas = readSchemas();
        List<String> wrong = new ArrayList<>();

        for (ReviewedSchema reviewed : ReviewedResponseSchemas.schemas()) {
            JsonNode properties = schemas.path(reviewed.dto().getSimpleName()).path("properties");
            for (String property : publishedProperties(schemas, reviewed)) {
                boolean listed = reviewed.nullable().contains(property);
                boolean admits = admitsNull(properties.path(property));
                if (listed && !admits) {
                    wrong.add(reviewed + "." + property + ": listed as nullable, but the document "
                            + "says " + properties.path(property));
                } else if (!listed && admits) {
                    wrong.add(reviewed + "." + property + ": the document says it admits null, but "
                            + "it is listed as non-null. Either the field was marked without the "
                            + "table being updated, or the table is now wrong");
                }
            }
        }

        assertThat(wrong)
                .as("the published document does not describe these properties the way "
                        + "ReviewedResponseSchemas does. Fix whichever is wrong, then regenerate "
                        + "with ./gradlew openApiSnapshot -PupdateOpenApiSnapshot")
                .isEmpty();
    }

    @Test
    @DisplayName("a reviewed property carries the annotation exactly when the table says so")
    void theAnnotationAgreesWithTheTable() {
        List<String> wrong = new ArrayList<>();

        for (ReviewedSchema reviewed : ReviewedResponseSchemas.schemas()) {
            for (BeanPropertyDefinition property : serializedProperties(reviewed.dto())) {
                boolean listed = reviewed.nullable().contains(property.getName());
                boolean marked = isMarkedNullable(property);
                if (listed && !marked) {
                    wrong.add(reviewed + "." + property.getName() + ": listed as nullable, but the "
                            + "field does not carry @Schema(nullable = true), so nothing carries "
                            + "it into the document");
                } else if (!listed && marked) {
                    wrong.add(reviewed + "." + property.getName() + ": marked "
                            + "@Schema(nullable = true), but listed as non-null");
                }
            }
        }

        assertThat(wrong)
                .as("the annotations on these fields do not match ReviewedResponseSchemas")
                .isEmpty();
    }

    @Test
    @DisplayName("the table is not empty and does not claim everything is nullable")
    void theTableIsNotVacuous() {
        List<ReviewedSchema> reviewed = ReviewedResponseSchemas.schemas();

        assertThat(reviewed)
                .as("no response schema is listed as reviewed, so this test is checking nothing")
                .isNotEmpty();
        assertThat(reviewed.stream().anyMatch(schema -> !schema.nullable().isEmpty()))
                .as("no reviewed schema declares a single nullable property, which would mean "
                        + "either the marking has been removed or nothing genuinely optional has "
                        + "been reviewed yet")
                .isTrue();
        assertThat(reviewed.stream().anyMatch(schema -> !schema.nonNull().isEmpty()))
                .as("no reviewed schema declares a single non-null property, which is the half "
                        + "that makes an unmarked field mean something")
                .isTrue();
    }

    /**
     * The property names the document publishes for a reviewed schema, failing if it publishes
     * none, since every check here would then pass over an empty set.
     */
    private static Set<String> publishedProperties(JsonNode schemas, ReviewedSchema reviewed) {
        JsonNode properties = schemas.path(reviewed.dto().getSimpleName()).path("properties");
        assertThat(properties.isObject())
                .as("the document has no schema named %s with properties. A renamed class, a "
                        + "schema springdoc names differently, or a DTO no endpoint returns any "
                        + "more; if the last, take it out of ReviewedResponseSchemas", reviewed)
                .isTrue();
        Set<String> names = new LinkedHashSet<>();
        properties.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /**
     * The properties a DTO serializes, under the same Jackson naming the document is generated
     * through. Needed because a property name is not always the field name: Jackson strips the
     * {@code is} prefix from a primitive boolean, so {@code isPrimary} publishes as
     * {@code primary}.
     */
    private static List<BeanPropertyDefinition> serializedProperties(Class<?> dto) {
        BeanDescription description = MAPPER.getSerializationConfig()
                .introspect(MAPPER.getTypeFactory().constructType(dto));
        return description.findProperties();
    }

    private static boolean isMarkedNullable(BeanPropertyDefinition property) {
        AnnotatedField field = property.getField();
        if (field == null) {
            return false;
        }
        Schema schema = field.getAnnotated().getAnnotation(Schema.class);
        return schema != null && schema.nullable();
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
            return MAPPER.readTree(Files.readString(DOCUMENT))
                    .path("components").path("schemas");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + DOCUMENT.toAbsolutePath(), e);
        }
    }
}
