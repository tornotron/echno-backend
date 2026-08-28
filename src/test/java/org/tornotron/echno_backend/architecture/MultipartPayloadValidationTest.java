package org.tornotron.echno_backend.architecture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ratchet against an endpoint deserializing its own payload: a controller may not parse a request
 * part itself, and may not ask for a {@code String} to be validated.
 *
 * <p>A multipart create endpoint cannot take a {@code @RequestBody}, because the body is the
 * multipart envelope, so its payload travels as a JSON string part. Every one of these endpoints
 * was written the same way: declare the part as {@code @RequestPart @Valid String data}, then call
 * {@code objectMapper.readValue(data, SomeCreationDto.class)}. The {@code @Valid} lands on the
 * {@code String}, a {@code String} declares no constraints, and the bean that comes out of
 * {@code readValue} is never offered to a validator. The endpoint reads as validated, documents a
 * 400 for a field that failed validation, and enforces nothing.
 *
 * <p>It is a quiet failure and a contagious one. Issue #490 counted 32 declared constraints across
 * 11 endpoints in five modules that had never run, every one of them copied from the endpoint
 * written before it. Fixing the eleven does nothing about the twelfth, which is what these rules
 * are for.
 *
 * <p>The fix each endpoint now uses is
 * {@code org.tornotron.echno_backend.common.payload.JsonPartBinder}, which parses and validates in
 * one call, so a caller cannot hold an unvalidated payload. The first rule bans the hand-parse that
 * goes around it. The second bans the annotation that made the bypass look deliberate, since
 * {@code @Valid} on a {@code String} has never meant anything and reads as though it does.
 *
 * <p>Runs under {@code @AnalyzeClasses} so the imported class graph comes from ArchUnit's own
 * cache, which every architecture test in this package shares and which holds it behind a soft
 * reference. See {@link UnboundedRepositoryReadTest} for why that matters in a 1 GB test JVM.
 */
@AnalyzeClasses(
        packages = "org.tornotron.echno_backend",
        importOptions = ImportOption.DoNotIncludeTests.class)
class MultipartPayloadValidationTest {

    /**
     * A Jackson read that turns raw JSON into an object. Both entry points count:
     * {@code ObjectMapper.readValue} is the one every affected endpoint used, and
     * {@code ObjectReader.readValue} is the same thing reached through
     * {@code objectMapper.reader()}.
     */
    private static final DescribedPredicate<JavaCall<?>> A_JACKSON_READ =
            new DescribedPredicate<>("a Jackson read of raw JSON") {
                @Override
                public boolean test(JavaCall<?> call) {
                    if (!call.getTarget().getName().startsWith("readValue")) {
                        return false;
                    }
                    return call.getTargetOwner().isAssignableTo(ObjectMapper.class)
                            || call.getTargetOwner().isAssignableTo(ObjectReader.class);
                }
            };

    @ArchTest
    static final ArchRule noControllerDeserializesItsOwnPayload = noClasses()
            .that().areMetaAnnotatedWith(Controller.class)
            .should().callMethodWhere(A_JACKSON_READ)
            .because("a payload a controller parses itself is never validated by Spring, which is "
                    + "how 32 constraints across 11 endpoints came to be unenforced; read the part "
                    + "with JsonPartBinder, which parses and validates in one call");

    /**
     * The annotation half of the same defect.
     *
     * <p>{@code @Valid} asks for the constraints declared on a type to be checked. {@code String}
     * declares none, so the annotation is inert wherever it lands on one. It is worth failing the
     * build over rather than deleting quietly, because it is the marker that made every one of
     * these endpoints look validated to the next person to read it, including in review.
     *
     * <p>Written against the imported parameters rather than in the fluent DSL, which has no
     * predicate for a parameter annotation.
     */
    @ArchTest
    static void noControllerAsksForAStringToBeValidated(JavaClasses productionClasses) {
        List<String> offenders = productionClasses.stream()
                .filter(javaClass -> javaClass.isMetaAnnotatedWith(Controller.class))
                .flatMap(javaClass -> javaClass.getMethods().stream())
                .flatMap(method -> method.getParameters().stream()
                        .filter(parameter -> parameter.isAnnotatedWith(Valid.class))
                        .filter(parameter -> parameter.getRawType().isEquivalentTo(String.class))
                        .map(parameter -> describe(method, parameter)))
                .sorted()
                .toList();

        assertThat(offenders)
                .as("@Valid on a String validates nothing, because a String declares no "
                        + "constraints; take the payload through JsonPartBinder instead, which "
                        + "validates the bean it parses")
                .isEmpty();
    }

    private static String describe(JavaMethod method, JavaParameter parameter) {
        return method.getFullName() + " parameter " + parameter.getIndex();
    }
}
