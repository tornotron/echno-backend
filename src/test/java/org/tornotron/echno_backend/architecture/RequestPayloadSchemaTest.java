package org.tornotron.echno_backend.architecture;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ratchet against an endpoint whose payload the published OpenAPI document cannot describe. Two
 * shapes qualify: a {@code data} payload that travels as a {@code String}, and a request body
 * declared as a {@code Map}. Both must say, in the document, what they actually hold.
 *
 * <p>A multipart endpoint cannot take a {@code @RequestBody}, because the body is the multipart
 * envelope, so the JSON payload travels as a {@code data} part and the endpoint reads it with
 * {@code JsonPartBinder}. The declared Java type of that parameter is {@code String}, and springdoc
 * documents what is declared, so every one of these endpoints published {@code type: string} for
 * its payload. The document named no field, no type and no constraint for a third of the write
 * surface, which meant a client could not be checked against it and a wrong field name was
 * indistinguishable from a right one. That is the gap tornotron/echno-core#49 hit when it went to
 * build a contract test and found nothing to check against.
 *
 * <p>The declared type cannot simply become the payload type. The clients append the part as a
 * plain form field, so it arrives with no {@code application/json} content type, and Spring answers
 * a typed {@code @RequestPart} parameter with 415 in that case. Verified against Spring Boot 3.4
 * rather than assumed. So the runtime keeps the {@code String} and the schema is declared
 * separately, with {@code @Parameter(schema = @Schema(implementation = ...))}, which springdoc
 * merges into the multipart schema in place of the string.
 *
 * <p>This rule is what stops the next endpoint from reopening the hole. Fixing the ones that exist
 * does nothing about the one written next week, which is the same reason
 * {@link MultipartPayloadValidationTest} exists beside it: that one keeps the payload validated,
 * this one keeps it described.
 *
 * <p>The map half is the other way the document ends up saying nothing. A {@code Map} body
 * publishes as {@code additionalProperties}, so it names no key, holds no type and can never fail
 * a check, and the services behind these bodies switch over a fixed set of keys with no
 * {@code default}, which makes an unrecognised key a silent no-op. They keep the map at runtime on
 * purpose, because a partial update has to tell an absent field from one explicitly set to null,
 * so what they publish is a {@code *UpdateFieldsDto} naming the keys the switch accepts.
 * {@link PartialUpdateSchemaContractTest} is what keeps those two in step.
 *
 * <p>Scope is the {@code data} payload convention and not every string that reaches an endpoint. A
 * {@code @RequestParam} is in scope only when it is named {@code data} outright. A
 * {@code @RequestPart} is in scope when it is named {@code data} or names nothing at all, since an
 * unnamed part takes the Java parameter name and a payload part written without a name is the exact
 * shape this rule is here to catch.
 *
 * <p>Runs under {@code @AnalyzeClasses} so the imported class graph comes from ArchUnit's own
 * cache, which every architecture test in this package shares. See {@link UnboundedRepositoryReadTest}
 * for why that matters in a 1 GB test JVM.
 */
@AnalyzeClasses(
        packages = "org.tornotron.echno_backend",
        importOptions = ImportOption.DoNotIncludeTests.class)
class RequestPayloadSchemaTest {

    private static final String PAYLOAD_PART = "data";

    /**
     * The map-shaped request bodies that stay undescribed, each with the reason.
     *
     * <p>A raw {@code Map} body is not automatically a defect. The entry here is a body whose keys
     * are the caller's own data rather than a fixed field list, so no schema could name them
     * without being wrong. Everything else that takes a map does so to keep an absent field
     * distinguishable from one explicitly set to null, and those all publish a
     * {@code *UpdateFieldsDto} that names their keys.
     *
     * <p>Keyed by the method's full name so an entry cannot quietly cover a method it was not
     * written for, and checked for staleness below so a fixed entry does not sit here forever.
     */
    private static final Map<String, String> ALLOWED = Map.of(
            "org.tornotron.echno_backend.keycloak.KeycloakManagementController"
                    + ".addRealmRoles(java.util.Map)",
            "the keys are the realm role names being created, which the caller chooses, so the "
                    + "body is genuinely a map and not a fixed set of fields");

    @ArchTest
    static void everyStringPayloadPartDeclaresItsSchema(JavaClasses productionClasses) {
        List<String> offenders = productionClasses.stream()
                .filter(javaClass -> javaClass.isMetaAnnotatedWith(Controller.class))
                .flatMap(javaClass -> javaClass.getMethods().stream())
                .flatMap(method -> method.getParameters().stream()
                        .filter(RequestPayloadSchemaTest::isStringPayloadParameter)
                        .filter(parameter -> declaredSchemaImplementation(parameter).isEmpty())
                        .map(parameter -> describe(method, parameter)))
                .sorted()
                .toList();

        assertThat(offenders)
                .as("a data payload declared as a String publishes as \"type: string\", which "
                        + "describes none of its fields; declare the payload type with "
                        + "@Parameter(schema = @Schema(implementation = SomePayloadDto.class)) so "
                        + "the document says what the part carries")
                .isEmpty();
    }

    @ArchTest
    static void everyMapRequestBodyDeclaresItsSchema(JavaClasses productionClasses) {
        List<String> offenders = mapRequestBodies(productionClasses)
                .filter(entry -> declaredRequestBodyImplementation(entry.parameter()).isEmpty())
                .filter(entry -> !ALLOWED.containsKey(entry.method().getFullName()))
                .map(Entry::description)
                .sorted()
                .toList();

        assertThat(offenders)
                .as("a request body declared as a Map publishes as additionalProperties, so the "
                        + "document names none of its keys and no key can ever fail against it; "
                        + "declare the fields with @RequestBody(content = @Content(schema = "
                        + "@Schema(implementation = SomeUpdateFieldsDto.class))), or add the "
                        + "endpoint to ALLOWED with the reason its keys really are the caller's own")
                .isEmpty();
    }

    /**
     * Keeps {@link #ALLOWED} from outliving what it excuses. An entry naming a method that no
     * longer takes an undescribed map is a stale exemption, and a stale exemption is how a rule
     * stops being a rule.
     */
    @ArchTest
    static void theAllowlistHasNoStaleEntries(JavaClasses productionClasses) {
        Set<String> stillUndescribed = mapRequestBodies(productionClasses)
                .filter(entry -> declaredRequestBodyImplementation(entry.parameter()).isEmpty())
                .map(entry -> entry.method().getFullName())
                .collect(Collectors.toSet());

        assertThat(ALLOWED.keySet())
                .as("every allowlist entry should name a method that still takes an undescribed "
                        + "map body; remove the ones that no longer do")
                .allMatch(stillUndescribed::contains);
    }

    /** One {@code @RequestBody Map} parameter, with the method it belongs to. */
    private record Entry(JavaMethod method, JavaParameter parameter) {
        String description() {
            return method.getFullName() + " parameter " + parameter.getIndex();
        }
    }

    private static java.util.stream.Stream<Entry> mapRequestBodies(JavaClasses productionClasses) {
        return productionClasses.stream()
                .filter(javaClass -> javaClass.isMetaAnnotatedWith(Controller.class))
                .flatMap(javaClass -> javaClass.getMethods().stream())
                .flatMap(method -> method.getParameters().stream()
                        .filter(parameter -> parameter.getRawType().isAssignableTo(Map.class))
                        .filter(parameter -> annotationOfType(parameter, RequestBody.class).isPresent())
                        .map(parameter -> new Entry(method, parameter)));
    }

    /**
     * The body type a parameter declares for the document, from the swagger
     * {@code @RequestBody(content = @Content(schema = @Schema(implementation = ...)))}. The swagger
     * annotation is a different type from Spring's {@code @RequestBody} and the two sit side by
     * side: Spring's says where the value comes from, swagger's says what the document should show.
     */
    private static Optional<JavaClass> declaredRequestBodyImplementation(JavaParameter parameter) {
        return annotationOfType(parameter, io.swagger.v3.oas.annotations.parameters.RequestBody.class)
                .flatMap(annotation -> nestedAnnotationArrayFirst(annotation, "content"))
                .flatMap(content -> nestedAnnotation(content, "schema"))
                .flatMap(schema -> schema.get("implementation"))
                .filter(JavaClass.class::isInstance)
                .map(JavaClass.class::cast)
                .filter(implementation -> !implementation.isEquivalentTo(Void.class));
    }

    /**
     * The first element of a nested annotation array member. {@code @Content} is declared as an
     * array on the swagger {@code @RequestBody}, and these endpoints declare exactly one.
     */
    private static Optional<JavaAnnotation<?>> nestedAnnotationArrayFirst(
            JavaAnnotation<?> annotation, String member) {
        return annotation.get(member)
                .filter(Object[].class::isInstance)
                .map(Object[].class::cast)
                .filter(values -> values.length > 0)
                .map(values -> values[0])
                .filter(JavaAnnotation.class::isInstance)
                .map(value -> (JavaAnnotation<?>) value);
    }

    /**
     * A {@code String} parameter carrying the JSON payload of a multipart or query-encoded write.
     *
     * <p>{@code @RequestParam} counts as well as {@code @RequestPart}: the attendance check-in and
     * clock-event endpoints take their payload as a query parameter, which springdoc publishes as a
     * string query parameter, and is if anything less informative than an undescribed part.
     */
    private static boolean isStringPayloadParameter(JavaParameter parameter) {
        if (!parameter.getRawType().isEquivalentTo(String.class)) {
            return false;
        }
        Optional<String> partName = declaredName(parameter, RequestPart.class);
        if (partName.isPresent()) {
            return partName.get().isEmpty() || PAYLOAD_PART.equals(partName.get());
        }
        return declaredName(parameter, RequestParam.class)
                .filter(PAYLOAD_PART::equals)
                .isPresent();
    }

    /**
     * The name a binding annotation declares, or an empty string when the annotation is present and
     * names nothing. Both {@code value} and {@code name} are read, since the two are aliases and
     * either may carry the name.
     */
    private static Optional<String> declaredName(JavaParameter parameter, Class<?> bindingAnnotation) {
        return annotationOfType(parameter, bindingAnnotation)
                .map(annotation -> stringMember(annotation, "value")
                        .filter(value -> !value.isEmpty())
                        .or(() -> stringMember(annotation, "name"))
                        .orElse(""));
    }

    /**
     * The payload type a parameter declares for the document, from either
     * {@code @Parameter(schema = @Schema(implementation = ...))} or a bare {@code @Schema} on the
     * parameter. Absent when neither names a type, which includes the case of a schema annotation
     * that carries only a description.
     */
    private static Optional<JavaClass> declaredSchemaImplementation(JavaParameter parameter) {
        Optional<JavaAnnotation<?>> schema = annotationOfType(parameter, Parameter.class)
                .flatMap(annotation -> nestedAnnotation(annotation, "schema"))
                .or(() -> annotationOfType(parameter, Schema.class));
        return schema
                .flatMap(annotation -> annotation.get("implementation"))
                .filter(JavaClass.class::isInstance)
                .map(JavaClass.class::cast)
                .filter(implementation -> !implementation.isEquivalentTo(Void.class));
    }

    private static Optional<JavaAnnotation<?>> annotationOfType(JavaParameter parameter, Class<?> type) {
        for (JavaAnnotation<?> annotation : parameter.getAnnotations()) {
            if (annotation.getRawType().isEquivalentTo(type)) {
                return Optional.of(annotation);
            }
        }
        return Optional.empty();
    }

    private static Optional<JavaAnnotation<?>> nestedAnnotation(JavaAnnotation<?> annotation, String member) {
        return annotation.get(member)
                .filter(JavaAnnotation.class::isInstance)
                .map(value -> (JavaAnnotation<?>) value);
    }

    private static Optional<String> stringMember(JavaAnnotation<?> annotation, String member) {
        return annotation.get(member)
                .filter(String.class::isInstance)
                .map(String.class::cast);
    }

    private static String describe(JavaMethod method, JavaParameter parameter) {
        return method.getFullName() + " parameter " + parameter.getIndex();
    }
}
