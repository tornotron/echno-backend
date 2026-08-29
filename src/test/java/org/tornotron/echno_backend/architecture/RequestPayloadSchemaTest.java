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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ratchet against an endpoint whose payload the published OpenAPI document cannot describe: a
 * {@code data} payload that travels as a {@code String} must say, in the document, what that string
 * actually holds.
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
