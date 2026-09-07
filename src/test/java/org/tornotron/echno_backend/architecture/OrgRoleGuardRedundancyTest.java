package org.tornotron.echno_backend.architecture;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.security.access.prepost.PreAuthorize;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * Forbids the guard that reads as two checks and decides as one:
 * {@code isMemberOfCurrentTenant() or hasAnyOrgRoleForCurrentTenant(...)}.
 *
 * <p>The role clause cannot change the outcome of that expression. Both sides open on
 * {@code TenantContext.getCurrentOrgId()} and refuse a null one, and {@code TenantFilter} sets it
 * only after confirming an {@code ORG_MEMBER_{id}} authority. A role-holder who is not a member
 * arrives with no organization in force and fails both clauses, so the guard decides on
 * membership alone while looking as though a role is enforced. #709 found the first, #717 swept
 * 36 more out of ten controllers, and both were written in good faith by someone reading the
 * expression rather than the filter.
 *
 * <p>The reason this is a rule and not a one-off cleanup is that the expression is easy to write
 * and its emptiness is invisible at the call site. It is also invisible to the obvious test: a
 * {@code @WebMvcTest} slice mocks {@code @orgSecurity}, so a {@code @PreAuthorize} test there
 * exercises the mock and passes on a stub's return value whatever the real beans would decide.
 * That is how the clause shipped green in {@code eec6c37}. A source-level rule needs no runtime
 * and cannot be satisfied by a stub.
 *
 * <p>Combining the two with {@code and} is meaningful and allowed: that narrows a guard to
 * members holding a role. Only {@code or} is refused.
 *
 * <p>Runs under {@code @AnalyzeClasses} so the imported class graph comes from ArchUnit's shared
 * cache, as every architecture test in this package does.
 */
@AnalyzeClasses(
        packages = "org.tornotron.echno_backend",
        importOptions = ImportOption.DoNotIncludeTests.class)
class OrgRoleGuardRedundancyTest {

    private static final String MEMBER_CHECK = "isMemberOfCurrentTenant()";
    private static final String ROLE_CHECK = "hasAnyOrgRoleForCurrentTenant(";

    @ArchTest
    static final ArchRule noGuardOrsAnOrgRoleAgainstTenantMembership = methods()
            .that().areAnnotatedWith(PreAuthorize.class)
            .should(notOrTheRoleCheckAgainstTheMembershipCheck());

    private static ArchCondition<JavaMethod> notOrTheRoleCheckAgainstTheMembershipCheck() {
        return new ArchCondition<>("not combine a current-tenant role check with the membership "
                + "check using 'or', because the role clause cannot change the outcome") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                String expression = method.getAnnotationOfType(PreAuthorize.class).value();
                if (!isRedundantOr(expression)) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(method, String.format(
                        "%s guards on \"%s\": the role clause is unreachable, because the tenant is "
                                + "only ever resolved for a member. Keep whichever clause decides "
                                + "and delete the other, or combine them with 'and'. See #717.",
                        method.getFullName(), expression)));
            }
        };
    }

    /**
     * Whether the expression ors the two checks together, in either order.
     *
     * <p>Deliberately coarse: it asks whether both checks appear and the word {@code or} appears
     * between them, rather than parsing SpEL. An expression that mentions both and never ors them
     * is rare enough that a false positive is cheap to rewrite, and being coarse is what makes the
     * rule survive reformatting and the addition of further clauses.
     */
    private static boolean isRedundantOr(String expression) {
        int memberAt = expression.indexOf(MEMBER_CHECK);
        int roleAt = expression.indexOf(ROLE_CHECK);
        if (memberAt < 0 || roleAt < 0) {
            return false;
        }
        int from = Math.min(memberAt, roleAt);
        int to = Math.max(memberAt, roleAt);
        return expression.substring(from, to).contains(" or ");
    }
}
