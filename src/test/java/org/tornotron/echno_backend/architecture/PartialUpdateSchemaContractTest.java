package org.tornotron.echno_backend.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.tornotron.echno_backend.architecture.PartialUpdateSurfaces.UpdateSurface;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds each partial-update schema to the keys its service actually accepts.
 *
 * <p>A partial update takes its body as a {@code Map<String, Object>}, because it has to tell a
 * field that was left out from a field that was sent as null, and a bean cannot: both arrive as a
 * null property, and several of these fields are cleared by sending an explicit null. The map is
 * therefore the right runtime shape and is not what this test is trying to change.
 *
 * <p>What the map costs is the published contract. A map is {@code additionalProperties} in
 * OpenAPI, so the document says nothing about which keys exist or what they hold, no key can ever
 * fail against it, and a caller sending a key the service does not know gets a 200 and no change.
 * The {@code *UpdateFieldsDto} classes supply that missing half: they are named as the request
 * schema on the endpoint and never deserialized into.
 *
 * <p>Which leaves the obvious failure mode. A schema that describes nothing at runtime is a schema
 * nothing keeps true, and a documentation class that drifts from the code is worse than no
 * documentation, because it reads as authoritative. So this test reads the {@code case "..."}
 * labels out of the service method's own source and fails when they and the schema's fields have
 * moved apart in either direction. Adding a key to a service switch without adding it to the schema
 * fails here, and so does the reverse.
 *
 * <p>The list of surfaces and the source reading live in {@link PartialUpdateSurfaces}, shared with
 * {@link PartialUpdateDefaultBranchTest}.
 */
class PartialUpdateSchemaContractTest {

    /**
     * A {@code case} label in a string switch. Matches both the arrow and colon forms, since the
     * services use one each.
     */
    private static final Pattern CASE_LABEL = Pattern.compile("case\\s+\"([^\"]+)\"");

    static List<UpdateSurface> surfaces() {
        return PartialUpdateSurfaces.surfaces();
    }

    @ParameterizedTest(name = "{0} describes exactly the keys its service accepts")
    @MethodSource("surfaces")
    @DisplayName("A published partial-update schema names every accepted key and no others")
    void schemaMatchesTheKeysTheServiceAccepts(UpdateSurface surface) throws IOException {
        Set<String> accepted = acceptedKeys(surface);
        Set<String> published = publishedFields(surface.schema());

        assertThat(published)
                .as("%s is the request schema published for this endpoint, so it has to name "
                        + "exactly the keys %s applies. A key in the service and not the schema is "
                        + "undocumented; a field in the schema and not the service is a documented "
                        + "field the endpoint silently ignores.",
                        surface.schema().getSimpleName(), surface.serviceClass())
                .containsExactlyInAnyOrderElementsOf(accepted);
    }

    /** The keys the service's update switch names, read out of the method's own source. */
    private static Set<String> acceptedKeys(UpdateSurface surface) throws IOException {
        String body = PartialUpdateSurfaces.methodBody(surface);
        Set<String> keys = new LinkedHashSet<>();
        Matcher matcher = CASE_LABEL.matcher(body);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        assertThat(keys)
                .as("no case labels found in %s; the method signature this test looks for has "
                        + "probably been reworded", surface.methodSignature())
                .isNotEmpty();
        return keys;
    }

    /** The properties the schema class declares, which is what springdoc publishes. */
    private static Set<String> publishedFields(Class<?> schema) {
        return java.util.Arrays.stream(schema.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
