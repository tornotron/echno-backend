package org.tornotron.echno_backend.architecture;

import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.architecture.PartialUpdateSurfaces.UpdateSurface;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every partial-update field says in the schema what an explicit null does to it.
 *
 * <p>A partial update is the one place in this API where null and absent are different requests.
 * Absence means the field is untouched. A null means either "clear this" or "no, and here is a
 * 400", and which of the two it means was, before #645, undiscoverable from the document: the
 * document declared nothing nullable at all, and the class-level description on
 * {@code TaskUpdateFieldsDto} asserted a blanket "a field sent as null is cleared" that two of its
 * own nine fields contradicted.
 *
 * <p>The two halves now compose. {@code required}, which #664 made accurate, says whether a field
 * may be absent. The 3.1 type union, which #661 made reachable, says whether it may be null when
 * present. Neither says anything about the other.
 *
 * <p>This test holds the annotations to {@link PartialUpdateSurfaces#surfaces()}: a field the
 * surface does not list as refusing a null must be marked {@code @Schema(nullable = true)}, and a
 * field it does list must not be. {@code OpenApiNullabilityTest} then carries the annotation into
 * {@code docs/openapi.json}, and {@code PartialUpdateNullBehaviourTest} holds the service to the
 * same list from the other side, so a field cannot be documented as clearing while the code
 * refuses, or the reverse.
 *
 * <p>Plain reflection over eight named classes rather than an ArchUnit scan: the list is the point
 * here, and a rule matching by package would quietly stop covering a DTO that moved.
 */
class PartialUpdateNullContractTest {

    @Test
    @DisplayName("a field that clears on null is declared nullable, and one that refuses is not")
    void everyPartialUpdateFieldDeclaresWhatANullDoes() {
        List<String> wrong = new ArrayList<>();

        for (UpdateSurface surface : PartialUpdateSurfaces.surfaces()) {
            Set<String> declared = fieldNames(surface);
            Set<String> unknown = new LinkedHashSet<>(surface.refusesNull());
            unknown.removeAll(declared);
            assertThat(unknown)
                    .as("%s lists these keys as refusing a null, but the schema class has no such "
                            + "field. A renamed field, or a key removed from the switch without "
                            + "being removed from PartialUpdateSurfaces", surface)
                    .isEmpty();

            for (Field field : schemaFields(surface)) {
                boolean refuses = surface.refusesNull().contains(field.getName());
                boolean marked = isMarkedNullable(field);
                if (refuses && marked) {
                    wrong.add(surface + "." + field.getName()
                            + ": refuses a null, but is marked @Schema(nullable = true), so the "
                            + "document invites a client to send one");
                } else if (!refuses && !marked) {
                    wrong.add(surface + "." + field.getName()
                            + ": clears on null, but is not marked @Schema(nullable = true), so the "
                            + "document says it may never be null and a client will not send one");
                }
            }
        }

        assertThat(wrong)
                .as("the published schema and PartialUpdateSurfaces disagree about what an explicit "
                        + "null does to these fields. Decide which is right, fix that one, and "
                        + "regenerate with ./gradlew openApiSnapshot -PupdateOpenApiSnapshot")
                .isEmpty();
    }

    @Test
    @DisplayName("the table names a real key for every refusal, and no surface refuses everything")
    void theTableIsNotVacuous() {
        List<UpdateSurface> surfaces = PartialUpdateSurfaces.surfaces();

        assertThat(surfaces)
                .as("no partial-update surface is listed, so this test is checking nothing")
                .isNotEmpty();
        assertThat(surfaces.stream().anyMatch(surface -> !surface.refusesNull().isEmpty()))
                .as("no surface refuses a null on any key, which would make the distinction this "
                        + "test exists for meaningless. Either the table has been emptied or the "
                        + "refusals have been removed from the services")
                .isTrue();

        for (UpdateSurface surface : surfaces) {
            assertThat(surface.refusesNull())
                    .as("%s refuses a null on every field it declares, which is a bean rather than "
                            + "a partial update; it should not be taking a map", surface)
                    .hasSizeLessThan(fieldNames(surface).size());
        }
    }

    private static List<Field> schemaFields(UpdateSurface surface) {
        return Arrays.stream(surface.schema().getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .toList();
    }

    private static Set<String> fieldNames(UpdateSurface surface) {
        return schemaFields(surface).stream().map(Field::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static boolean isMarkedNullable(Field field) {
        Schema schema = field.getAnnotation(Schema.class);
        return schema != null && schema.nullable();
    }
}
