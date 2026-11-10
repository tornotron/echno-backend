package org.tornotron.echno_backend.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Ratchet for the move onto the access matrix: no {@code @PreAuthorize} decides by role name any
 * more, outside the few controllers {@link #ALLOWED} names with their reason. A guard asks
 * {@code @access} for an area and an action instead, so who may do it is a cell an organization can
 * see and change on its Roles &amp; Permissions screen.
 *
 * <p>What counts as a role-name guard: an {@code @orgSecurity} check with a role in its name
 * ({@code hasOrgRole}, {@code hasAnyOrgRole}, {@code hasAnyOrgRoleForCurrentTenant},
 * {@code isSelfOrHasAnyOrgRole}), Spring's {@code hasRole}, {@code hasAnyRole}, {@code hasAuthority}
 * and {@code hasAnyAuthority}, and the domain beans that only wrapped role lists
 * ({@code @attendanceSecurity}, {@code @inspectionSecurity.canIntakeObservations}).
 * {@code hasRole('platform-admin')} is allowed everywhere: the platform admin runs the platform for
 * every organization at once and is no cell of any organization's matrix.
 *
 * <p>Checks of who the caller is, not what role they hold, are not role-name guards and are allowed:
 * {@code @orgSecurity.isSelfInCurrentTenant}, {@code isSelfUser}, {@code isCurrentTenant} and
 * {@code isMemberOfCurrentTenant}.
 *
 * <p>Runs under {@code @AnalyzeClasses} so the imported class graph comes from ArchUnit's shared
 * cache, as every architecture test in this package does.
 */
@AnalyzeClasses(
        packages = "org.tornotron.echno_backend",
        importOptions = ImportOption.DoNotIncludeTests.class)
class RoleGuardRatchetTest {

    /**
     * The controllers still allowed a role-name guard, by simple name, and why. Remove an entry when
     * its controller goes; do not add one to get a new guard past this test.
     */
    static final Map<String, String> ALLOWED = Map.of(
            "KeycloakManagementController",
            "Keycloak's own administration, absent under WorkOS, removed with Keycloak",
            "ObservationIntakeController",
            "machine logins: a producer is a service account with no employee row, and the matrix answers "
                    + "through an employee");

    private static final String PLATFORM_ADMIN = "hasRole('platform-admin')";

    private static final List<Pattern> ROLE_NAME_GUARDS = List.of(
            Pattern.compile("@orgSecurity\\.\\w*Role\\w*\\("),
            Pattern.compile("\\bhas(Any)?Role\\("),
            Pattern.compile("\\bhas(Any)?Authority\\("),
            Pattern.compile("@attendanceSecurity\\."),
            Pattern.compile("@inspectionSecurity\\.canIntakeObservations\\("));

    @ArchTest
    static final ArchRule noHandlerDecidesByRoleName = methods()
            .that().areAnnotatedWith(PreAuthorize.class)
            .should(notDecideByRoleName());

    @ArchTest
    static final ArchRule noClassDecidesByRoleName = classes()
            .that().areAnnotatedWith(PreAuthorize.class)
            .should(notDecideByRoleNameOnTheClass());

    @ArchTest
    static void everyAllowedControllerStillNeedsItsEntry(JavaClasses classes) {
        Set<String> stillUsing = new TreeSet<>();
        for (JavaClass type : classes) {
            for (JavaMethod method : type.getMethods()) {
                if (method.isAnnotatedWith(PreAuthorize.class)
                        && isRoleNameGuard(method.getAnnotationOfType(PreAuthorize.class).value())) {
                    stillUsing.add(type.getSimpleName());
                }
            }
        }
        assertThat(ALLOWED.keySet()).as("an allowed controller with no role-name guard left must leave the list")
                .allMatch(stillUsing::contains);
    }

    static boolean isRoleNameGuard(String expression) {
        String rest = expression.replace(PLATFORM_ADMIN, "");
        return ROLE_NAME_GUARDS.stream().anyMatch(pattern -> pattern.matcher(rest).find());
    }

    private static ArchCondition<JavaMethod> notDecideByRoleName() {
        return new ArchCondition<>("not decide by role name outside the allowed controllers") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                String expression = method.getAnnotationOfType(PreAuthorize.class).value();
                if (isRoleNameGuard(expression) && !ALLOWED.containsKey(method.getOwner().getSimpleName())) {
                    events.add(SimpleConditionEvent.violated(method, String.format(
                            "%s guards on \"%s\", a role name. Ask @access.can for the area and action instead.",
                            method.getFullName(), expression)));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> notDecideByRoleNameOnTheClass() {
        return new ArchCondition<>("not decide by role name outside the allowed controllers") {
            @Override
            public void check(JavaClass type, ConditionEvents events) {
                String expression = type.getAnnotationOfType(PreAuthorize.class).value();
                if (isRoleNameGuard(expression) && !ALLOWED.containsKey(type.getSimpleName())) {
                    events.add(SimpleConditionEvent.violated(type, String.format(
                            "%s guards on \"%s\", a role name. Ask @access.can for the area and action instead.",
                            type.getName(), expression)));
                }
            }
        };
    }
}
