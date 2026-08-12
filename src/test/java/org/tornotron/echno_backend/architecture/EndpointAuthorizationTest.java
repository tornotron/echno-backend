package org.tornotron.echno_backend.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * Ratchet for the endpoint-authorization sweep: every request-mapped method in a
 * {@code @RestController} must carry a {@code @PreAuthorize} (on the method or the
 * class), so a new endpoint cannot ship without an explicit authorization decision.
 *
 * <p>{@link #GRANDFATHERED} lists controllers that predate this rule and are still
 * unguarded. They are being migrated batch by batch; as a controller is fully
 * guarded it is removed from this set. The rule keeps the gap from growing and
 * forces every new controller to be guarded. A deliberately public endpoint must
 * use {@code @PreAuthorize("permitAll()")} rather than be left un-annotated.
 */
class EndpointAuthorizationTest {

    /**
     * Controllers still to be guarded (see the audit's endpoint-authorization
     * sweep). Shrinks to empty as each is migrated. Do NOT add to this set.
     */
    private static final Set<String> GRANDFATHERED = Set.of(
            "AttendanceController", "AttendanceControllerWeb",
            "AttendanceRegularizationController", "AttendanceRegularizationControllerWeb",
            "AttendanceSettingsController", "AttendanceSettingsControllerWeb",
            "MovementRecordController", "MovementRecordControllerWeb",
            "ShiftTimingController", "ShiftTimingControllerWeb",
            "AuthController",
            "FeatureController", "PlanController", "SubscriptionController",
            "EmployeeControllerWeb",
            "LeaveApprovalController", "LeaveBalanceController",
            "LeavePolicyController", "LeaveRequestController",
            "OrganizationWebController",
            "ReportController",
            "TaskControllerWeb");

    private static final DescribedPredicate<JavaClass> NOT_GRANDFATHERED =
            new DescribedPredicate<>("not a grandfathered controller") {
                @Override
                public boolean test(JavaClass javaClass) {
                    return !GRANDFATHERED.contains(javaClass.getSimpleName());
                }
            };

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("org.tornotron.echno_backend");

    @Test
    void everyControllerEndpointHasAnAuthorizationGuard() {
        ArchRule rule = methods()
                .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                .and().areDeclaredInClassesThat(NOT_GRANDFATHERED)
                .and().areMetaAnnotatedWith(RequestMapping.class)
                .should().beAnnotatedWith(PreAuthorize.class)
                .orShould().beDeclaredInClassesThat().areAnnotatedWith(PreAuthorize.class);

        rule.check(PRODUCTION_CLASSES);
    }
}
