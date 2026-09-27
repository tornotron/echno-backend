package org.tornotron.echno_backend.architecture;

import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodReference;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import jakarta.persistence.EntityManager;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.retry.TransactionalWorkRunner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Work handed to {@link TenantScopedJobRunner#callForTenant} or {@code runForTenant} reaches
 * the database only through a transaction boundary.
 *
 * <p>Those two entry points pin the tenant and open no transaction. The {@code orgFilter} is
 * enabled by {@code HibernateFilterConfig} when a {@code @Transactional} method of ours is
 * entered, so a repository called straight from the work runs on a session nobody filtered and
 * reads every organization's rows. A query that returns scalars or a projection does not reach
 * the load listener either, so nothing downstream notices. That was #877, in the toolbox-talk
 * reminder, and the reminder was written as the reference module that others copy.
 *
 * <p>What counts as a boundary: {@link TransactionalWorkRunner}, {@link TransactionRetryTemplate},
 * the runner's own {@code InTransaction} entry points, and a call into another class's method
 * that carries a method-level {@code @Transactional} which opens or joins a transaction. It has
 * to be method level and on another class. The aspect matches the annotation on the method, not
 * the class, and a call to a method on the same object never passes through the proxy, so an
 * annotated method called on {@code this} is no boundary at all.
 *
 * <p>The walk starts from the calls written inside lambdas and the method references in any
 * method that calls one of the two entry points, and follows calls into this codebase's classes
 * until it meets a boundary or a repository. ArchUnit attributes a lambda's calls to the method
 * that declares it and cannot say which lambda a call came from, which sets the limits:
 *
 * <ul>
 *   <li>In a method that also calls a boundary, a repository call written inside a lambda is
 *       taken to be inside that boundary. That is the {@code retryTemplate.execute(() -> ...)}
 *       idiom every compliant caller uses, and the price is that a bare repository call in the
 *       runner's own lambda, beside a retry lambda in the same method, goes unseen.</li>
 *   <li>Every lambda of a method that calls the runner is treated as runner work. A cross-tenant
 *       scan written as a lambda next to the runner call is reported too; move it into a method
 *       of its own, which reads better anyway.</li>
 * </ul>
 *
 * <p>An interface or abstract method is followed into every implementation in this codebase, so
 * a bean injected by its interface is not a way around the rule.
 */
@AnalyzeClasses(
        packages = "org.tornotron.echno_backend",
        importOptions = ImportOption.DoNotIncludeTests.class)
class TenantJobTransactionBoundaryTest {

    private static final String PRODUCTION_PACKAGE = "org.tornotron.echno_backend.";

    private static final Set<String> PLAIN_ENTRY_POINTS = Set.of("callForTenant", "runForTenant");

    private static final Set<String> TRANSACTIONAL_ENTRY_POINTS =
            Set.of("callForTenantInTransaction", "runForTenantInTransaction");

    /** Propagations that put the method's work inside a transaction the aspect has seen. */
    private static final Set<Propagation> OPENS_OR_JOINS = EnumSet.of(
            Propagation.REQUIRED, Propagation.REQUIRES_NEW, Propagation.NESTED, Propagation.MANDATORY);

    @ArchTest
    static void workHandedToThePlainRunnerReachesTheDatabaseOnlyThroughATransaction(JavaClasses classes) {
        Map<String, List<String>> offenders = new LinkedHashMap<>();

        for (JavaClass javaClass : classes) {
            for (JavaCodeUnit unit : javaClass.getCodeUnits()) {
                if (!callsPlainEntryPoint(unit)) {
                    continue;
                }
                for (List<String> path : pathsToBareDatabaseAccess(unit, classes)) {
                    offenders.put(String.join(" -> ", path), path);
                }
            }
        }

        assertThat(new TreeSet<>(offenders.keySet()))
                .as("TenantScopedJobRunner.callForTenant/runForTenant pin the tenant but open no "
                        + "transaction, so HibernateFilterConfig never enables orgFilter and a "
                        + "repository called from the work reads every organization's rows (#877). "
                        + "Use callForTenantInTransaction/runForTenantInTransaction, or put the "
                        + "database work behind TransactionalWorkRunner, TransactionRetryTemplate "
                        + "or a @Transactional method on another bean")
                .isEmpty();
    }

    private static boolean callsPlainEntryPoint(JavaCodeUnit unit) {
        return unit.getMethodCallsFromSelf().stream().anyMatch(call ->
                isRunner(call.getTargetOwner()) && PLAIN_ENTRY_POINTS.contains(call.getName()));
    }

    /**
     * Breadth first from the runner work in {@code start}, so each reported path is the shortest
     * one and reads as the explanation.
     */
    private static List<List<String>> pathsToBareDatabaseAccess(JavaCodeUnit start, JavaClasses classes) {
        List<List<String>> found = new ArrayList<>();
        Map<JavaCodeUnit, JavaCodeUnit> arrivedFrom = new LinkedHashMap<>();
        Set<JavaCodeUnit> seen = new HashSet<>();
        Deque<JavaCodeUnit> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);

        while (!queue.isEmpty()) {
            JavaCodeUnit current = queue.poll();
            boolean isStart = current.equals(start);
            boolean hasBoundary = callsBoundary(current);

            for (JavaAccess<?> access : accessesOf(current)) {
                boolean inLambdaOrReference = access.isDeclaredInLambda() || isReference(access);
                if (isStart && !inLambdaOrReference) {
                    // Outside every lambda of the method that calls the runner: not runner work.
                    continue;
                }
                if (!isStart && hasBoundary && inLambdaOrReference) {
                    // Handed to the boundary this method opens; see the class comment.
                    continue;
                }
                JavaClass owner = access.getTargetOwner();
                if (isDatabaseAccess(owner)) {
                    if (isStart && hasBoundary) {
                        continue;
                    }
                    found.add(describe(start, current, arrivedFrom, owner.getSimpleName() + "." + access.getName()));
                    continue;
                }
                if (!isOurs(owner) || isBoundaryHelper(access)) {
                    continue;
                }
                for (JavaCodeUnit next : resolve(access.getTarget(), classes)) {
                    if (next.getOwner().equals(current.getOwner()) || !isTransactionalBoundary(next)) {
                        if (seen.add(next)) {
                            arrivedFrom.put(next, current);
                            queue.add(next);
                        }
                    }
                }
            }
        }
        return found;
    }

    private static List<JavaAccess<?>> accessesOf(JavaCodeUnit unit) {
        return Stream.concat(unit.getMethodCallsFromSelf().stream(), unit.getMethodReferencesFromSelf().stream())
                .<JavaAccess<?>>map(access -> access)
                .toList();
    }

    private static boolean isReference(JavaAccess<?> access) {
        return access instanceof JavaMethodReference;
    }

    private static boolean callsBoundary(JavaCodeUnit unit) {
        return accessesOf(unit).stream().anyMatch(TenantJobTransactionBoundaryTest::isBoundaryHelper);
    }

    /** The helpers that open a transaction around the work they are handed. */
    private static boolean isBoundaryHelper(JavaAccess<?> access) {
        JavaClass owner = access.getTargetOwner();
        return owner.isEquivalentTo(TransactionalWorkRunner.class)
                || owner.isEquivalentTo(TransactionRetryTemplate.class)
                || (isRunner(owner) && TRANSACTIONAL_ENTRY_POINTS.contains(access.getName()));
    }

    /** A method-level {@code @Transactional} that opens or joins a transaction, when called through its proxy. */
    private static boolean isTransactionalBoundary(JavaCodeUnit unit) {
        Optional<Transactional> transactional = unit.tryGetAnnotationOfType(Transactional.class);
        return transactional.isPresent() && OPENS_OR_JOINS.contains(transactional.get().propagation());
    }

    /**
     * The code a call can land in. An interface or abstract method has no body, so it is
     * followed into the implementations this codebase declares.
     */
    private static Set<JavaCodeUnit> resolve(AccessTarget target, JavaClasses classes) {
        Set<JavaCodeUnit> units = new HashSet<>();
        Optional<? extends JavaCodeUnit> member = target.resolveMember().map(JavaCodeUnit.class::cast);
        if (member.isEmpty()) {
            return units;
        }
        JavaCodeUnit declared = member.get();
        boolean hasBody = !(declared instanceof JavaMethod method)
                || !method.getModifiers().contains(JavaModifier.ABSTRACT);
        if (hasBody) {
            units.add(declared);
            return units;
        }
        for (JavaClass implementation : declared.getOwner().getAllSubclasses()) {
            if (!isOurs(implementation)) {
                continue;
            }
            implementation.tryGetMethod(declared.getName(),
                            declared.getRawParameterTypes().stream().map(JavaClass::getName).toArray(String[]::new))
                    .ifPresent(units::add);
        }
        return units;
    }

    private static List<String> describe(JavaCodeUnit start, JavaCodeUnit lastHop,
                                         Map<JavaCodeUnit, JavaCodeUnit> arrivedFrom, String databaseCall) {
        List<String> path = new ArrayList<>();
        path.add(databaseCall);
        for (JavaCodeUnit step = lastHop; step != null && !step.equals(start); step = arrivedFrom.get(step)) {
            path.add(0, step.getOwner().getSimpleName() + "." + step.getName());
        }
        path.add(0, start.getOwner().getSimpleName() + "." + start.getName());
        return path;
    }

    private static boolean isRunner(JavaClass owner) {
        return owner.isEquivalentTo(TenantScopedJobRunner.class);
    }

    private static boolean isDatabaseAccess(JavaClass owner) {
        return owner.isAssignableTo(Repository.class) || owner.isAssignableTo(EntityManager.class);
    }

    private static boolean isOurs(JavaClass javaClass) {
        return javaClass.getName().startsWith(PRODUCTION_PACKAGE);
    }
}
