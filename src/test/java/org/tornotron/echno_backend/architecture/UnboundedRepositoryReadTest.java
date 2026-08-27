package org.tornotron.echno_backend.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ratchet against unbounded repository reads: no production class may call a Spring Data
 * {@code findAll()} that has no limit on how many rows it returns.
 *
 * <p>An unbounded read loads every row its table holds, maps every one and serialises the lot,
 * so the cost grows with a tenant's history and nothing stops it. The
 * {@code hibernate.default_batch_fetch_size} setting keeps the query <em>count</em> flat as a
 * result set grows but cannot bound the row <em>count</em>, which makes this the one scale axis
 * batch fetching does nothing for. It is invisible on a demo tenant and it is the first thing to
 * fall over on a client with a few years of tasks, issues, materials and attendance behind them.
 *
 * <p>The rule bans the call itself rather than trying to prove that a controller returns its
 * result. Proving reachability through the service layer needs transitive call-graph analysis,
 * which fails for the wrong reasons and points at the wrong line. Banning the call has no false
 * positives, catches the CSV and PDF paths a controller-shaped rule would miss, and fails at the
 * line that introduced the problem.
 *
 * <p>Both the no-argument {@code findAll()} and {@code findAll(Sort)} count. Sorting an unbounded
 * read still returns every row, and it is exactly the variant an earlier audit's grep walked past.
 * {@code findAll(Pageable)} and {@code findAll(Example, Pageable)} are bounded and are not matched.
 *
 * <p>{@link #ALLOWED} names the classes that may still make the call, each with the reason. Split
 * into two halves that are read differently: the permanent entries are bounded by the nature of
 * what they read, and the temporary ones are simply not fixed yet. Row counts today are not a
 * reason for either. On a per-client production model one tenant's small reference table is
 * another's long tail, so an entry earns the permanent half only when the ceiling comes from what
 * the table <em>is</em>.
 *
 * <p>Runs under {@code @AnalyzeClasses} rather than importing the class graph itself. ArchUnit
 * holds a graph of this size in memory, and the test JVM is capped at 1 GB while already caching
 * a Spring context per distinct test configuration, so a second private import is not free. The
 * annotation routes through ArchUnit's own cache, which is shared across every architecture test
 * with the same import spec and holds it behind a soft reference, so the collector can reclaim it
 * under pressure rather than the suite running out of heap.
 */
@AnalyzeClasses(
        packages = "org.tornotron.echno_backend",
        importOptions = ImportOption.DoNotIncludeTests.class)
class UnboundedRepositoryReadTest {

    /**
     * Classes permitted to make an unbounded repository read.
     *
     * <p>Do NOT add to this set to make a build pass. Bound the read instead: take a
     * {@code Pageable}, or cap it with
     * {@code org.tornotron.echno_backend.common.pagination.UnpagedResultCap}.
     */
    private static final Set<String> ALLOWED = Set.of(
            // --- Deliberate and permanent. Bounded by what the table is, not by how big it is today.

            // The chart of accounts: bounded by the accounting structure a tenant defines. Volume
            // lives in JournalEntry, which is paged. A partial chart would misreport every total.
            "AccountService",
            // Chart-of-accounts import and export, same structure. A partial extract would be wrong.
            "AccountCsvService",
            // A tenant's own bank accounts: bounded by how many accounts a company operates, not by
            // how long it has operated.
            "CompanyBankAccountService",
            // The cost-category reference table: bounded by the tenant's cost breakdown structure.
            "CostCategoryService",
            // The billing feature catalogue: a fixed enum-like table shipped with the product.
            "FeatureService",
            // Leave types a tenant defines (annual, sick, casual): bounded by policy design, not by
            // headcount and not by elapsed time.
            "LeavePolicyService",

            // --- Temporary. Genuinely unbounded, waiting on the client work that moves each caller
            // onto the paginated endpoint that already exists. Tracked by issue #473. Every entry
            // here is a table that grows with a tenant's site activity and never shrinks.

            "AssetService",
            "EmployeeService",
            "ExpenseService",
            "GoodsReceivedNoteService",
            "IndentService",
            "IssueService",
            "MaterialConsumptionService",
            "MaterialService",
            "PurchaseOrderService",
            "ReceiptService",
            "SiteTransferService",
            "StockAdjustmentService",
            "StorageLocationService",
            "SubContractService",
            "VendorService"
    );

    private static final DescribedPredicate<JavaClass> NOT_ALLOWED =
            new DescribedPredicate<>("not allowed an unbounded repository read") {
                @Override
                public boolean test(JavaClass javaClass) {
                    return !ALLOWED.contains(javaClass.getSimpleName());
                }
            };

    private static final DescribedPredicate<JavaCall<?>> UNBOUNDED_FIND_ALL =
            new DescribedPredicate<>("an unbounded repository findAll") {
                @Override
                public boolean test(JavaCall<?> call) {
                    if (!"findAll".equals(call.getTarget().getName())) {
                        return false;
                    }
                    if (!call.getTargetOwner().isAssignableTo(Repository.class)) {
                        return false;
                    }
                    List<JavaClass> parameters = call.getTarget().getRawParameterTypes();
                    if (parameters.isEmpty()) {
                        return true;
                    }
                    return parameters.size() == 1
                            && Sort.class.getName().equals(parameters.get(0).getName());
                }
            };

    @ArchTest
    static final ArchRule noProductionClassReadsAWholeTable = noClasses()
            .that(NOT_ALLOWED)
            .should().callMethodWhere(UNBOUNDED_FIND_ALL)
            .because("an unpaginated findAll loads every row a tenant has, so the response and "
                    + "the memory it costs grow without bound; take a Pageable, or cap the read "
                    + "with UnpagedResultCap");

    /**
     * Keeps the allowlist honest in the other direction: an entry that no longer makes the call
     * has to go.
     *
     * <p>Without this, entries outlive the reads they excused and the set stops describing
     * anything. It is also what makes the ratchet tighten: fixing a read and leaving its entry
     * behind fails here, so the list can only shrink.
     */
    @ArchTest
    static void theAllowlistHasNoStaleEntries(JavaClasses productionClasses) {
        Set<String> stillReadingWholeTables = productionClasses.stream()
                .filter(javaClass -> javaClass.getCodeUnitCallsFromSelf().stream()
                        .anyMatch(UNBOUNDED_FIND_ALL))
                .map(JavaClass::getSimpleName)
                .collect(Collectors.toSet());

        assertThat(ALLOWED)
                .as("every allowlist entry must still make an unbounded read; remove the ones that "
                        + "no longer do")
                .isSubsetOf(stillReadingWholeTables);
    }

    /**
     * The allowlist matches on simple name, following the {@link EndpointAuthorizationTest}
     * precedent. That is only safe while no two production classes share one, so this asserts it,
     * rather than leaving a future duplicate to exempt an unrelated class in silence.
     */
    @ArchTest
    static void noAllowlistEntryIsAmbiguous(JavaClasses productionClasses) {
        Map<String, Long> countsBySimpleName = productionClasses.stream()
                .map(JavaClass::getSimpleName)
                .filter(ALLOWED::contains)
                .collect(Collectors.groupingBy(name -> name, Collectors.counting()));

        assertThat(countsBySimpleName)
                .as("an allowlisted simple name must identify exactly one class")
                .allSatisfy((name, count) -> assertThat(count)
                        .describedAs("classes named %s", name)
                        .isEqualTo(1L));
    }
}
