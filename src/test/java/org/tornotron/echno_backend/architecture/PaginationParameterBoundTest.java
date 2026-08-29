package org.tornotron.echno_backend.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import org.springframework.stereotype.Controller;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ratchet against an endpoint declaring its own paging parameters: a controller may take the page
 * bounds only as a {@code PageQuery} or a Spring Data {@code Pageable}, never as a loose
 * {@code pageNo} or {@code pageSize} of its own.
 *
 * <p>Forty-seven endpoints took the pair as bare {@code int} request parameters with nothing
 * between the caller and {@code PageRequest.of}. A page index below zero and a page size below
 * one were caught only by {@code PageRequest} itself, a value that is not a number failed during
 * binding and came back as a 500, and no value at all was too large: {@code pageSize=1000000} was
 * a legal request, which undoes on the paginated endpoint the row cap
 * {@code UnpagedResultCap.MAX_ROWS} puts on its unpaginated sibling, and on the endpoints whose
 * mapper costs a query per row it multiplies the query count and not merely the response size.
 *
 * <p>Fixing forty-seven handlers is forty-seven chances to forget, and the forty-eighth is
 * written by copying one of them. So the bound lives in
 * {@code org.tornotron.echno_backend.common.pagination.PageQuery}, and this rule bans the
 * declaration that goes around it. It is the same shape of guard as
 * {@link MultipartPayloadValidationTest}, for the same reason: the defect spreads by copy, so the
 * ratchet has to sit on the pattern rather than on the instances.
 *
 * <p>Written against reflected parameter names rather than the fluent DSL, which has no predicate
 * for one. Names survive into the bytecode because the build compiles with {@code -parameters},
 * which Spring already depends on for binding these very parameters.
 *
 * <p>Runs under {@code @AnalyzeClasses} so the imported class graph comes from ArchUnit's own
 * cache, which every architecture test in this package shares and which holds it behind a soft
 * reference. See {@link UnboundedRepositoryReadTest} for why that matters in a 1 GB test JVM.
 */
@AnalyzeClasses(
        packages = "org.tornotron.echno_backend",
        importOptions = ImportOption.DoNotIncludeTests.class)
class PaginationParameterBoundTest {

    /**
     * Parameter names that mean "the caller chose the page". Matched case-insensitively, so a
     * {@code pagesize} or {@code PageSize} spelled some other way is caught too.
     */
    private static final Set<String> PAGING_PARAMETER_NAMES = Set.of("pageno", "pagesize");

    @ArchTest
    static void noControllerDeclaresItsOwnPagingParameters(JavaClasses productionClasses) {
        List<String> offenders = productionClasses.stream()
                .filter(javaClass -> javaClass.isMetaAnnotatedWith(Controller.class))
                .map(JavaClass::reflect)
                .flatMap(controller -> List.of(controller.getDeclaredMethods()).stream())
                .flatMap(method -> List.of(method.getParameters()).stream()
                        .filter(parameter -> PAGING_PARAMETER_NAMES.contains(
                                parameter.getName().toLowerCase()))
                        .map(parameter -> describe(method, parameter)))
                .sorted()
                .toList();

        assertThat(offenders)
                .as("a paging parameter a controller declares itself is bounded by nothing, which "
                        + "is how pageSize=1000000 became a legal request on every paginated "
                        + "endpoint; take the page as a PageQuery, which carries the bounds and "
                        + "answers a breach with a 400 naming the parameter")
                .isEmpty();
    }

    private static String describe(Method method, Parameter parameter) {
        return method.getDeclaringClass().getName() + "." + method.getName()
                + " parameter " + parameter.getName();
    }
}
