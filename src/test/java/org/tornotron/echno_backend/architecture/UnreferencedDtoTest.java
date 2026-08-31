package org.tornotron.echno_backend.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fails when a transfer object exists that no production code can reach.
 *
 * <p>An orphaned DTO is not a runtime cost, it is a contract cost. The class compiles, so nothing
 * complains, and a reader who finds it in the tree reasonably assumes some endpoint answers with
 * that shape. {@code echno-core} is hand-maintained with no code generation, so the way a shape
 * gets into the client library is that somebody reads the backend and writes the matching type by
 * hand; a DTO that never leaves the server is therefore a live invitation to write a client type
 * for a response that will never arrive.
 *
 * <p>They appear by subtraction rather than by addition, which is why a grep at review time does
 * not catch them. {@code LeaveRequestSimpleDto} (issue #618) was produced by a static
 * {@code convertToSimpleDto} helper; the MapStruct migration replaced that whole converter class
 * with a mapper that only produces {@code LeaveRequestDto}, and the DTO was left behind with its
 * producer gone. The diff that orphaned it was a diff that deleted code, and it was correct.
 *
 * <p>The rule is deliberately narrow. It asks only whether anything in the production classes
 * mentions the type at all, which includes the MapStruct implementations the annotation processor
 * writes, so a DTO that is returned, nested inside another DTO, or named as a request body all
 * count as reached. Being referenced is not the same as being useful and this test does not claim
 * it is. It catches the one failure mode where the answer is not a judgement call: nothing at all
 * refers to the class.
 */
@AnalyzeClasses(
        packages = "org.tornotron.echno_backend",
        importOptions = ImportOption.DoNotIncludeTests.class)
class UnreferencedDtoTest {

    /**
     * The one orphan left standing, and why it is not deleted here.
     *
     * <p>{@code UserCreationDto} is the request body of a {@code POST /users} endpoint that is
     * still in the tree as a commented-out block in {@code UserControllerWeb}. Commit
     * {@code 0a56a62} removed {@code UserService.addUser}, so nothing can build one today and
     * nothing publishes its schema, but the commented block reads as an endpoint somebody meant to
     * bring back rather than as a leftover. Whether user creation returns to the API at all is a
     * product question, not a cleanup, and issue #620 carries it.
     *
     * <p>An entry here is a debt, not a licence. Anything added to this set needs an issue that
     * says what the class is waiting for.
     */
    private static final Set<String> ORPHANED_PENDING_A_DECISION =
            Set.of("org.tornotron.echno_backend.user.dto.UserCreationDto");

    /**
     * Every {@code *Dto} in a {@code dto} package is mentioned by some other production class.
     *
     * @param productionClasses The imported production classes, supplied by ArchUnit.
     */
    @ArchTest
    static void everyTransferObjectIsReachedFromProductionCode(JavaClasses productionClasses) {
        List<String> orphaned = productionClasses.stream()
                .filter(UnreferencedDtoTest::isATransferObjectOfOurs)
                .filter(UnreferencedDtoTest::isReachedOnlyByItself)
                .map(JavaClass::getName)
                .filter(name -> !ORPHANED_PENDING_A_DECISION.contains(name))
                .sorted()
                .toList();

        assertThat(orphaned)
                .as("a DTO no production class mentions has no producer and no consumer, and "
                        + "publishes a response shape that never arrives: either wire it up or "
                        + "delete it")
                .isEmpty();
    }

    /**
     * Whether a class is one of ours, a top-level concrete {@code *Dto} in a {@code dto} package.
     *
     * <p>Nested types are excluded because their outer class always refers to them, so the
     * question this test asks has a fixed answer for them.
     *
     * @param javaClass The imported class to judge.
     * @return {@code true} when the class is in scope for the rule.
     */
    private static boolean isATransferObjectOfOurs(JavaClass javaClass) {
        return javaClass.getPackageName().startsWith("org.tornotron.echno_backend")
                && javaClass.getPackageName().contains(".dto")
                && javaClass.getSimpleName().endsWith("Dto")
                && javaClass.getEnclosingClass().isEmpty()
                && !javaClass.isInterface()
                && !javaClass.getModifiers().contains(JavaModifier.ABSTRACT);
    }

    /**
     * Whether the only production classes depending on a DTO are the DTO and its own nested types.
     *
     * @param transferObject The DTO to judge.
     * @return {@code true} when nothing outside the class itself refers to it.
     */
    private static boolean isReachedOnlyByItself(JavaClass transferObject) {
        return transferObject.getDirectDependenciesToSelf().stream()
                .map(dependency -> dependency.getOriginClass().getName())
                .allMatch(origin -> origin.equals(transferObject.getName())
                        || origin.startsWith(transferObject.getName() + "$"));
    }
}
