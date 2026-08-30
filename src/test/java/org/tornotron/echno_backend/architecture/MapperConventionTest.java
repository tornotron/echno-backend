package org.tornotron.echno_backend.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import org.mapstruct.Mapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ratchet on how a conversion is written: one mechanism for the whole codebase, MapStruct.
 *
 * <p>Issue #522 opened on the suspicion that the conversion layer was inconsistent. Counted, it
 * mostly was not: 52 of 59 mapper classes were already MapStruct, there was no reflective copying
 * anywhere and no static {@code fromEntity} factories on the DTOs. What was left was seven
 * hand-written classes, 583 lines, doing by hand what the annotation processor writes. Six of them
 * were exactly that and are now generated. This test is what keeps the eighth from appearing: it
 * is easier to add a hand-written mapper than to notice one has been added.
 *
 * <p>The value of the rule is not speed. A hand-written mapper is not slower than a generated one,
 * and none of the seven was on the cost path {@link MapperDatabaseAccessTest} guards. It is that a
 * generated mapper cannot silently drop a field: add a column to an entity and the DTO, and
 * MapStruct fills it, while a hand-written builder chain goes on returning null for it until
 * somebody notices in the browser.
 */
@AnalyzeClasses(
        packages = "org.tornotron.echno_backend",
        importOptions = ImportOption.DoNotIncludeTests.class)
class MapperConventionTest {

    /**
     * The one hand-written mapper left, and the reason it is still hand-written.
     *
     * <p>{@code BillingMapper} is not a field copier. Its job is that there is exactly one route
     * from persistent state to response state and it goes through an immutable snapshot: every
     * {@code toXDto(entity)} is one line delegating to {@code toXDto(toXSnapshot(entity))}, so the
     * value the subscription cache holds and the value the response carries are built from the
     * same reading of the row. Point MapStruct at {@code Plan -> PlanDto} and it will generate a
     * direct field-by-field route, and that invariant disappears with nothing failing to say so.
     *
     * <p>It also resolves three entitlement flags against one {@code Instant} captured per call.
     * {@code SubscriptionSnapshot} deliberately stores timestamps rather than booleans, so that a
     * cached entry answers "expired?" for now rather than for the moment it was cached; as
     * MapStruct expressions the three would each read the clock separately and two flags on one
     * response could straddle a period boundary and contradict each other.
     *
     * <p>And it is a static utility with a private constructor on purpose. Its callers include
     * plain unit tests with no Spring context and an integration test standing in for a concurrent
     * reader, none of which want a bean.
     *
     * <p>Converting it is possible and would be worse: an abstract {@code @Mapper} holding
     * hand-written delegation and an {@code @AfterMapping} that captures the instant is the same
     * hand-written mapping with an annotation on it.
     */
    private static final Set<String> HAND_WRITTEN_BY_DECISION =
            Set.of("org.tornotron.echno_backend.billing.dto.BillingMapper");

    /**
     * Every class named {@code *Mapper} converts through MapStruct, bar the recorded exception.
     *
     * @param productionClasses The imported production classes, supplied by ArchUnit.
     */
    @ArchTest
    static void everyMapperIsGenerated(JavaClasses productionClasses) {
        List<String> handWritten = productionClasses.stream()
                .filter(MapperConventionTest::isAMapperOfOurs)
                .filter(javaClass -> !javaClass.isAnnotatedWith(Mapper.class))
                .map(JavaClass::getName)
                .filter(name -> !HAND_WRITTEN_BY_DECISION.contains(name))
                .sorted()
                .toList();

        assertThat(handWritten)
                .as("a conversion is written once, by the annotation processor: a hand-written "
                        + "mapper copies fields by hand and goes on returning null for the next "
                        + "one somebody adds. If a mapper genuinely holds behaviour MapStruct "
                        + "cannot express, add it to HAND_WRITTEN_BY_DECISION with the reason")
                .isEmpty();
    }

    /**
     * The exception list names something real. A recorded exception that has since been deleted or
     * renamed would otherwise sit there excusing nothing, and the next reader would take it as
     * evidence that hand-written mappers are ordinary.
     *
     * @param productionClasses The imported production classes, supplied by ArchUnit.
     */
    @ArchTest
    static void theRecordedExceptionStillExists(JavaClasses productionClasses) {
        Set<String> present = productionClasses.stream()
                .map(JavaClass::getName)
                .filter(HAND_WRITTEN_BY_DECISION::contains)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(present)
                .as("HAND_WRITTEN_BY_DECISION should name classes that exist")
                .isEqualTo(HAND_WRITTEN_BY_DECISION);
    }

    /**
     * Whether a class is one of this codebase's mappers.
     *
     * <p>Excludes the {@code *MapperImpl} classes MapStruct writes, which sit in the same packages
     * and carry {@code @Generated} rather than {@code @Mapper}, and anything nested inside a
     * mapper.
     *
     * @param javaClass The class to judge.
     * @return {@code true} for a mapper written in this repository.
     */
    private static boolean isAMapperOfOurs(JavaClass javaClass) {
        String simpleName = javaClass.getSimpleName();
        return javaClass.getName().startsWith("org.tornotron.echno_backend.")
                && simpleName.endsWith("Mapper")
                && !javaClass.isNestedClass();
    }
}
