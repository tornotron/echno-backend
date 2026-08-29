package org.tornotron.echno_backend.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.EntityManager;
import org.mapstruct.AfterMapping;
import org.mapstruct.BeforeMapping;
import org.mapstruct.Mapper;
import org.springframework.data.repository.Repository;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ratchet against database reads inside a MapStruct hook: a mapper converts the object it was
 * handed and asks nobody anything.
 *
 * <p>A {@code @BeforeMapping} or {@code @AfterMapping} method runs once for every object mapped,
 * and the generated mapper calls it from inside the per-row loop. A query in there therefore costs
 * one round trip per row, multiplied again for every DTO that nests the mapped one, and the call
 * site shows none of it: the service reads {@code page.map(mapper::toDto)}, which looks free. That
 * is how {@code MaterialMapper} came to charge two aggregate queries per material, so a page of
 * fifty cost a hundred extra reads and a ten-line indent cost twenty, and how
 * {@code StorageLocationMapper} came to count its stock rows once per row across four listings.
 *
 * <p>{@code hibernate.default_batch_fetch_size} does not soften this. It batches lazy
 * <em>association</em> loads; a hook calling a repository issues explicit statements the setting
 * cannot see. So unlike an N+1 over a mapped collection, this one does not get cheaper on its own.
 *
 * <p>The fix is always the same shape and it already existed in the codebase before this rule did:
 * whatever the mapper cannot reach from the object it was given, the caller reads once for the
 * whole page and passes in. {@code MaterialMapper.toWithStockDto} took its aggregates as arguments
 * and cost nothing; {@code toDto} fetched its own and cost everything. A MapStruct
 * {@code @Context} parameter carries the batch through nested mappers, so depth stops multiplying
 * query count.
 *
 * <p>The rule follows calls transitively rather than only looking for a repository named directly
 * in the hook, because neither offender named one: {@code MaterialMapper} went through
 * {@code InventoryService}. A direct-call rule would have passed the very code it exists to
 * prevent. Reachability is computed over method calls only and only through this codebase's own
 * classes, which keeps it precise: work a hook delegates to something that never touches the
 * database, such as signing a storage URL, is not a database read and is not reported.
 */
@AnalyzeClasses(
        packages = "org.tornotron.echno_backend",
        importOptions = ImportOption.DoNotIncludeTests.class)
class MapperDatabaseAccessTest {

    private static final String PRODUCTION_PACKAGE = "org.tornotron.echno_backend.";

    /**
     * What counts as touching the database. A Spring Data repository covers every query the
     * codebase issues today; {@link EntityManager} is there so dropping to JPA directly is not a
     * way around the rule.
     */
    private static final DescribedPredicate<JavaClass> DATABASE_ACCESS =
            describe("a database access point",
                    javaClass -> javaClass.isAssignableTo(Repository.class)
                            || javaClass.isAssignableTo(EntityManager.class));

    /**
     * Guards the direct case: a mapper has no business holding a repository at all.
     *
     * <p>Cheaper and blunter than the reachability check below, and it fails at the field rather
     * than at the hook, which is the line a reviewer would rather see. It is not a substitute:
     * a mapper that injects a service instead slips straight past it.
     */
    @ArchTest
    static final ArchRule noMapperHoldsARepository = noClasses()
            .that().areAnnotatedWith(Mapper.class)
            .should().dependOnClassesThat(assignableTo(Repository.class))
            .because("a mapper converts what it is handed; whatever it cannot reach from that "
                    + "object is read once for the whole page by the caller and passed in");

    /**
     * The real rule: nothing a mapping hook calls may end in a database read.
     *
     * <p>Written as a plain assertion rather than as an {@code ArchRule} because the useful part
     * of the failure is the path, and a condition that reports only the offending method leaves
     * the reader to find the query themselves.
     *
     * @param productionClasses The imported production classes, supplied by ArchUnit.
     */
    @ArchTest
    static void noMappingHookReachesTheDatabase(JavaClasses productionClasses) {
        Map<String, List<String>> offenders = new LinkedHashMap<>();

        for (JavaClass javaClass : productionClasses) {
            for (JavaMethod method : javaClass.getMethods()) {
                if (!isMappingHook(method)) {
                    continue;
                }
                pathToDatabase(method).ifPresent(path ->
                        offenders.put(javaClass.getSimpleName() + "." + method.getName(), path));
            }
        }

        assertThat(offenders)
                .as("a @BeforeMapping or @AfterMapping hook runs once per mapped row, so a query "
                        + "reached from one costs a round trip per row and the call site shows "
                        + "nothing; read it once for the whole page in the caller and pass it in, "
                        + "through a MapStruct @Context where the mapper is nested")
                .isEmpty();
    }

    private static boolean isMappingHook(JavaMethod method) {
        return method.isAnnotatedWith(AfterMapping.class) || method.isAnnotatedWith(BeforeMapping.class);
    }

    /**
     * Walks the method-call graph out of a hook, looking for a database read.
     *
     * <p>Breadth first, so the reported path is the shortest one, which is the one that reads as
     * an explanation. Only calls landing in this codebase are followed: a framework method is a
     * leaf, either a database read or of no interest.
     *
     * @param hook The mapping hook to walk out from.
     * @return The call path from the hook to a database read, or empty where there is none.
     */
    private static Optional<List<String>> pathToDatabase(JavaMethod hook) {
        Map<JavaCodeUnit, JavaCodeUnit> arrivedFrom = new LinkedHashMap<>();
        Set<JavaCodeUnit> seen = new HashSet<>();
        Deque<JavaCodeUnit> queue = new ArrayDeque<>();

        seen.add(hook);
        queue.add(hook);

        while (!queue.isEmpty()) {
            JavaCodeUnit current = queue.poll();
            for (JavaMethodCall call : current.getMethodCallsFromSelf()) {
                if (DATABASE_ACCESS.test(call.getTargetOwner())) {
                    return Optional.of(describePath(hook, current, arrivedFrom, call));
                }
                if (!isOurs(call.getTargetOwner())) {
                    continue;
                }
                Optional<JavaMethod> called = call.getTarget().resolveMember();
                if (called.isEmpty() || !seen.add(called.get())) {
                    continue;
                }
                arrivedFrom.put(called.get(), current);
                queue.add(called.get());
            }
        }
        return Optional.empty();
    }

    private static List<String> describePath(JavaMethod hook, JavaCodeUnit lastHop,
                                             Map<JavaCodeUnit, JavaCodeUnit> arrivedFrom,
                                             JavaMethodCall databaseCall) {
        List<String> path = new ArrayList<>();
        path.add(name(databaseCall.getTargetOwner(), databaseCall.getName()));
        for (JavaCodeUnit step = lastHop; step != null && !step.equals(hook); step = arrivedFrom.get(step)) {
            path.add(0, name(step.getOwner(), step.getName()));
        }
        path.add(0, name(hook.getOwner(), hook.getName()));
        return path;
    }

    private static String name(JavaClass owner, String member) {
        return owner.getSimpleName() + "." + member;
    }

    private static boolean isOurs(JavaClass javaClass) {
        return javaClass.getName().startsWith(PRODUCTION_PACKAGE);
    }
}
