package org.tornotron.echno_backend.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *
 * <p>The second rule pins the guard itself, for the areas the roles matrix in the admin guide
 * ({@code docs/admin-guide/roles-and-permissions.md} in echno-docs) names and #853 aligned:
 * issues, storage locations, stock adjustments and the third-party
 * registers, and since #855 materials. An area that moves onto the access
 * matrix leaves this table, and its own guard test and equivalence test pin it instead
 * ({@code AssetGuardsTest} and {@code AssetAccessEquivalenceIT}, {@code IndentGuardsTest} and
 * {@code IndentAccessEquivalenceIT}, {@code FinanceBillingGuardsTest} and
 * {@code FinanceBillingAccessEquivalenceIT} for construction invoices). A guard on one of those controllers must name exactly the roles the matrix gives
 * the endpoint's row, so a loosening arrives as a failed build rather than a line in a diff.
 * A new endpoint on a pinned controller must be added to {@link #MATRIX} with its row, which
 * is the same decision the matrix asks of it.
 *
 * <p>Runs under {@code @AnalyzeClasses} so the imported class graph comes from ArchUnit's own
 * cache, which every architecture test in this package shares and which holds it behind a soft
 * reference. See {@link UnboundedRepositoryReadTest} for why that matters in a 1 GB test JVM.
 */
@AnalyzeClasses(
        packages = "org.tornotron.echno_backend",
        importOptions = ImportOption.DoNotIncludeTests.class)
class EndpointAuthorizationTest {

    /**
     * Controllers exempt from the guard rule. The endpoint-authorization sweep is
     * complete, so this is empty: every controller endpoint must be guarded. Do NOT
     * add to this set - guard the endpoint instead (use {@code permitAll()} for a
     * deliberately public one).
     */
    private static final Set<String> GRANDFATHERED = Set.of();

    private static final DescribedPredicate<JavaClass> NOT_GRANDFATHERED =
            new DescribedPredicate<>("not a grandfathered controller") {
                @Override
                public boolean test(JavaClass javaClass) {
                    return !GRANDFATHERED.contains(javaClass.getSimpleName());
                }
            };

    @ArchTest
    static final ArchRule everyControllerEndpointHasAnAuthorizationGuard = methods()
            .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .and().areDeclaredInClassesThat(NOT_GRANDFATHERED)
            .and().areMetaAnnotatedWith(RequestMapping.class)
            .should().beAnnotatedWith(PreAuthorize.class)
            .orShould().beDeclaredInClassesThat().areAnnotatedWith(PreAuthorize.class);

    // ---- The roles matrix, pinned -------------------------------------------------------

    /** Membership of the current tenant, with no role asked for. */
    private static final Set<String> MEMBER = Set.of("member");
    private static final Set<String> ADMIN_OR_PM = Set.of("system-admin", "project-manager");

    /**
     * One row of the matrix applied to one controller: the methods it covers, by exact name or
     * by a {@code prefix*}, and the roles the guard must name. Rows are matched in order, so a
     * broad {@code *} row for a controller comes after its specific ones.
     */
    private record Pin(String controller, Set<String> roles, List<String> methods) {
        boolean covers(JavaMethod method) {
            for (String pattern : methods) {
                if (pattern.equals("*")) {
                    return true;
                }
                if (pattern.endsWith("*") && method.getName().startsWith(pattern.substring(0, pattern.length() - 1))) {
                    return true;
                }
                if (pattern.equals(method.getName())) {
                    return true;
                }
            }
            return false;
        }
    }

    private static Pin pin(String controller, Set<String> roles, String... methods) {
        return new Pin(controller, roles, List.of(methods));
    }

    /**
     * The matrix rows #853 aligned, endpoint by endpoint. Where the matrix has no row of its own
     * (storage locations, vendors, labour, sub-contracts) the row was added to the admin
     * guide from the guard the backend already enforced, so this table and the guide say the
     * same thing and this test is what keeps them saying it.
     */
    private static final List<Pin> MATRIX = List.of(
            // Issues are on the access matrix, and IssueGuardsTest and IssueAccessEquivalenceIT pin
            // them: any member reads, the pair writes.

            // Storage locations: the stores tier reads, only the administrator shapes the list.
            // Storage locations are on the access matrix, and StorageLocationGuardsTest and
            // StorageLocationAccessEquivalenceIT pin them.

            // Stock adjustments are on the access matrix, as the inventory area, and StoresGuardsTest
            // and StoresAccessEquivalenceIT pin them: the stores tier reads and raises, the pair
            // decides and deletes.

            // Vendors are on the access matrix, and VendorGuardsTest and VendorAccessEquivalenceIT pin
            // them: the store reads who delivers and how to reach them, and a vendor's financials and
            // every write stay the administrator's.

            // Labour and sub-contracts are on the access matrix, as the areas labour and construction,
            // and LabourGuardsTest, SubContractGuardsTest and their equivalence tests pin them: labour
            // stays HR's and the administrator's throughout, any member reads a sub-contract and the
            // pair changes it.

            // Materials, indents and purchase orders (#855) are on the access matrix. Materials are
            // their own area, and StoresGuardsTest and StoresAccessEquivalenceIT pin them: the stores
            // tier reads and sets reorder thresholds (#746), the pair creates and edits, and deleting
            // and removing a threshold override stay with the administrator. IndentGuardsTest and
            // PurchaseOrderGuardsTest pin the other two.

            // Risk register: it sits with the project, so any member reads and the pair records,
            // changes, imports and removes. Both twins.
            pin("ProjectRiskController", MEMBER, "read*"),
            pin("ProjectRiskController", ADMIN_OR_PM, "createRisk", "importRisks", "updateRisk", "deleteRisk"),
            pin("ProjectRiskControllerWeb", MEMBER, "read*"),
            pin("ProjectRiskControllerWeb", ADMIN_OR_PM, "createRisk", "importRisks", "updateRisk", "deleteRisk"));


    private static final Set<String> PINNED_CONTROLLERS = new TreeSet<>(
            MATRIX.stream().map(Pin::controller).toList());

    private static final DescribedPredicate<JavaClass> COVERED_BY_THE_MATRIX =
            new DescribedPredicate<>("a controller the roles matrix pins") {
                @Override
                public boolean test(JavaClass javaClass) {
                    return PINNED_CONTROLLERS.contains(javaClass.getSimpleName());
                }
            };

    private static final Pattern ROLE_GUARD = Pattern.compile(
            "^@orgSecurity\\.hasAnyOrgRoleForCurrentTenant\\(([^)]*)\\)$");
    private static final Pattern QUOTED = Pattern.compile("'([^']+)'");
    private static final String MEMBER_GUARD = "@orgSecurity.isMemberOfCurrentTenant()";

    @ArchTest
    static final ArchRule matrixCoveredEndpointsCarryTheGuardTheMatrixNames = methods()
            .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .and().areDeclaredInClassesThat(COVERED_BY_THE_MATRIX)
            .and().areMetaAnnotatedWith(RequestMapping.class)
            .should(carryTheGuardTheMatrixNames());

    private static ArchCondition<JavaMethod> carryTheGuardTheMatrixNames() {
        return new ArchCondition<>("carry exactly the guard the roles matrix names for the endpoint") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                String controller = method.getOwner().getSimpleName();
                String endpoint = controller + "." + method.getName();
                Optional<Pin> pin = MATRIX.stream()
                        .filter(p -> p.controller().equals(controller) && p.covers(method))
                        .findFirst();
                if (pin.isEmpty()) {
                    events.add(SimpleConditionEvent.violated(method, endpoint
                            + " is on a controller the roles matrix pins but has no row in MATRIX;"
                            + " add it with the roles its matrix row names"));
                    return;
                }
                Set<String> actual = guardOf(method);
                if (!actual.equals(pin.get().roles())) {
                    events.add(SimpleConditionEvent.violated(method, endpoint
                            + " is guarded as " + actual + " but the roles matrix names " + pin.get().roles()));
                }
            }
        };
    }

    /**
     * The guard as a set of role names, with {@code member} standing for plain tenant membership.
     * Anything this test does not know how to read is returned verbatim so it fails the
     * comparison loudly rather than being mistaken for one of the known shapes.
     */
    private static Set<String> guardOf(JavaMethod method) {
        String expression = method.isAnnotatedWith(PreAuthorize.class)
                ? method.getAnnotationOfType(PreAuthorize.class).value()
                : method.getOwner().isAnnotatedWith(PreAuthorize.class)
                        ? method.getOwner().getAnnotationOfType(PreAuthorize.class).value()
                        : "<unguarded>";
        String trimmed = expression.replaceAll("\\s+", "");
        if (trimmed.equals(MEMBER_GUARD)) {
            return MEMBER;
        }
        Matcher roles = ROLE_GUARD.matcher(trimmed);
        if (roles.matches()) {
            Set<String> named = new TreeSet<>();
            Matcher quoted = QUOTED.matcher(roles.group(1));
            while (quoted.find()) {
                named.add(quoted.group(1));
            }
            return named;
        }
        return Set.of(expression);
    }
}
