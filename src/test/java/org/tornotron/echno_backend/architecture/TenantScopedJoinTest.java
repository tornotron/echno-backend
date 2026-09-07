package org.tornotron.echno_backend.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import org.springframework.data.jpa.repository.Query;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ratchet on the one shape the tenant filter does not cover: an entity joined explicitly in a
 * query, whose rows are never selected.
 *
 * <h2>What the two isolation mechanisms actually reach</h2>
 *
 * <p>The Hibernate {@code orgFilter} narrows a query <em>root</em> and a filtered collection. It
 * does not narrow {@code JOIN o.employees e}. {@code TenantIsolationLoadListener} cannot cover the
 * gap either, and not by oversight: it runs on a post-load, and an entity that is only joined is
 * never loaded, so there is no event to judge. Where both of those hold at once, nothing is
 * scoping that part of the query, and it fails silently rather than loudly.
 *
 * <p>{@code OrganizationLookupUnderTheOrgFilterIT} measured that against a database rather than
 * reasoning about it, after two readings of the same evidence came out wrong in opposite
 * directions. It is the fact this rule is built on. #698 was the first live consequence found and
 * #718 the sweep that followed; this exists so the sweep does not have to be redone from scratch,
 * and so a new query of the same shape is answered for when it is written rather than years later.
 *
 * <h2>What the rule asks</h2>
 *
 * <p>A query carrying an explicit join is clear when any one of three structural facts holds, and
 * otherwise has to appear in {@link #CROSS_TENANT_JOINS} with a reason:
 *
 * <ol>
 *   <li><b>Every join is a fetch join.</b> A fetched entity is loaded, so the load listener sees
 *       it and refuses a foreign row. Fail-closed rather than silent, which is the whole
 *       difference.
 *   <li><b>The root is tenant-scoped and every non-fetch join is an association join.</b> The
 *       filter has narrowed the root to the tenant, and an association join walks a foreign key
 *       out of a row that is already in it, so the join can narrow the result further but cannot
 *       widen it past the tenant.
 *   <li>Neither, and it is registered below.
 * </ol>
 *
 * <p>The distinction the second point turns on is between {@code JOIN cs.material m}, which is an
 * association reached from an alias, and {@code JOIN MaterialLocationThreshold t ON ...}, which
 * names an entity and is tied to the root only by whatever the {@code ON} clause says. The first
 * inherits the root's scoping through the foreign key. The second inherits nothing, so if its
 * {@code ON} clause does not carry a tenant predicate of its own, rows from any organization can
 * satisfy it. Both spell the word {@code JOIN}; only one of them is safe by construction.
 *
 * <p>A native query is never narrowed at all, so it is registered whenever it joins. The
 * uncovered-native-query problem is wider than joins and is documented in
 * {@code docs/MULTI_TENANCY_FILTER_GUIDE.md}; this rule takes only the part that overlaps its own
 * subject.
 *
 * <h2>What the rule does not claim</h2>
 *
 * <p>It does not decide whether a registered query is a defect. A deliberate cross-tenant read is
 * a legitimate thing to write, and three of the entries below are exactly that. What it refuses is
 * an <em>undeclared</em> one: the shape has to be recognised and answered for by whoever writes
 * it, in the place a reviewer will look.
 *
 * <p>It also does not analyse subqueries or projections. The root of the outermost query is what
 * it reads, which is what decides whether the result is tenant-scoped.
 *
 * <p>Runs under {@code @AnalyzeClasses} for the reason {@link UnboundedRepositoryReadTest} gives:
 * a private import of a graph this size does not fit beside the Spring context cache in the test
 * JVM's heap, and the annotation routes through ArchUnit's shared, softly-referenced cache.
 */
@AnalyzeClasses(
        packages = "org.tornotron.echno_backend",
        importOptions = ImportOption.DoNotIncludeTests.class)
class TenantScopedJoinTest {

    /**
     * Queries whose joins the tenant filter does not scope, each with why that is acceptable.
     *
     * <p>Do NOT add an entry to make a build pass. Reach for one of the three structural fixes
     * first: fetch the joined entity so the load listener judges it, root the query on a
     * tenant-scoped entity and reach the rest through associations, or put the tenant predicate in
     * the {@code ON} clause so the join carries its own scoping. An entry belongs here only when
     * the query is deliberately answering a question about an organization other than the one the
     * request is scoped to, and something outside the query establishes the caller's right to ask
     * it.
     */
    private static final Map<String, String> CROSS_TENANT_JOINS = Map.of(
            "OrganizationRepository#findAllByUserEmail",
            "The organization switcher. Its whole purpose is to list every organization the "
                    + "signed-in user is employed by, so being narrowed to the current tenant "
                    + "would reduce it to the one the user is already in. Scoped by the user's "
                    + "own email rather than by a caller-supplied id, and it is a caller reading "
                    + "their own memberships.",

            "OrganizationRepository#findByIdAndUserEmail",
            "A membership probe, and it must not be read as a tenant check. It answers whether "
                    + "the caller has an Employee row in the organization they named, for any "
                    + "organization, and establishes nothing about what they may do there. "
                    + "Organization is the tenant root, so it carries no filter and the load "
                    + "listener has nothing to say about it either. Every caller has to answer "
                    + "for the target in its own guard: see LeavePolicyController.duplicatePolicy "
                    + "and @orgSecurity.hasAnyOrgRole(#targetOrganizationId, ...). See #698.",

            "LowStockRepository#findLowStockForOrganization",
            "The join onto CurrentStock is an entity join and carries its own tenant predicate: "
                    + "ON cs.organization.id = :organizationId, with the same parameter the "
                    + "filtered root is narrowed by and read from TenantContext by LowStockService. "
                    + "It is a left join specifically so a material with no stock row anywhere "
                    + "still appears, which an association join could not express.",

            "LowStockRepository#findLowStockAtStorageLocation",
            "Same shape and same reason for MaterialLocationThreshold: the ON clause carries "
                    + "t.organization.id = :organizationId. The override is left-joined so a "
                    + "material without one falls back to its global reorder level.",

            "ComplianceGenerationJobRepository#findSweepCandidates",
            "The nightly sweep's one scan, and cross-tenant by necessity: its job is to work out "
                    + "which tenants have work to do, so it runs before any tenant is known. "
                    + "Native, scalars only, never an entity, and the caller establishes a tenant "
                    + "per project before anything is enqueued. Declared with @WithoutTenant at "
                    + "the call site.");

    /**
     * The rule. A query whose joins the filter does not reach has to be registered above.
     */
    @ArchTest
    static void everyUnscopedJoinIsAccountedFor(JavaClasses productionClasses) {
        Set<String> unaccounted = new TreeSet<>(findUnscopedJoins(productionClasses));
        unaccounted.removeAll(CROSS_TENANT_JOINS.keySet());

        assertThat(unaccounted)
                .as("the orgFilter does not narrow an entity joined explicitly, and the load "
                        + "listener never sees one that is not selected, so a query of this shape "
                        + "is scoped by nothing. Fetch the joined entity, root the query on a "
                        + "tenant-scoped entity and reach the rest through associations, or put "
                        + "the tenant predicate in the ON clause. If the cross-tenant reach is "
                        + "deliberate, register it in CROSS_TENANT_JOINS with the reason")
                .isEmpty();
    }

    /**
     * Keeps the registry honest in the other direction, the way
     * {@link UnboundedRepositoryReadTest} does: an entry whose query no longer has the shape has
     * to go, so the list can only shrink and never silently outlives what it excused.
     */
    @ArchTest
    static void theRegistryHasNoStaleEntries(JavaClasses productionClasses) {
        assertThat(CROSS_TENANT_JOINS.keySet())
                .as("every registered query must still join across the tenant boundary; remove "
                        + "the entries that no longer do")
                .isSubsetOf(findUnscopedJoins(productionClasses));
    }

    /**
     * Every reason is written out, so an entry cannot be added as a bare name.
     *
     * <p>Takes the imported classes it does not read because ArchUnit requires exactly that
     * parameter on an {@code @ArchTest} method.
     */
    @ArchTest
    static void everyRegistryEntryGivesAReason(JavaClasses productionClasses) {
        assertThat(CROSS_TENANT_JOINS)
                .allSatisfy((query, reason) -> assertThat(reason)
                        .describedAs("the reason recorded for %s", query)
                        .hasSizeGreaterThan(60));
    }

    // -------------------------------------------------------------------------------------
    // The scan.
    // -------------------------------------------------------------------------------------

    private static Set<String> findUnscopedJoins(JavaClasses productionClasses) {
        Set<String> tenantScopedEntities = productionClasses.stream()
                .filter(javaClass -> javaClass.isAssignableTo(TenantScopedEntity.class))
                .map(javaClass -> javaClass.getSimpleName().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        Set<String> found = new LinkedHashSet<>();
        for (var javaClass : productionClasses) {
            for (JavaMethod method : javaClass.getMethods()) {
                method.tryGetAnnotationOfType(Query.class).ifPresent(query -> {
                    boolean unscoped = isUnscoped(query.value(), query.nativeQuery(), tenantScopedEntities)
                            || isUnscoped(query.countQuery(), query.nativeQuery(), tenantScopedEntities);
                    if (unscoped) {
                        found.add(javaClass.getSimpleName() + "#" + method.getName());
                    }
                });
            }
        }
        return found;
    }

    /**
     * Whether one query string carries a join the tenant filter does not reach.
     *
     * @param jpql The query, which may be empty: {@code countQuery} usually is.
     * @param nativeQuery Whether the query bypasses Hibernate's mapping entirely, and with it the
     *         filter.
     * @param tenantScopedEntities Lower-cased simple names of the entities that carry the filter.
     */
    private static boolean isUnscoped(String jpql, boolean nativeQuery, Set<String> tenantScopedEntities) {
        if (jpql == null || jpql.isBlank()) {
            return false;
        }
        List<String> tokens = tokenize(jpql);
        List<String> joinTargets = nonFetchJoinTargets(tokens);
        if (joinTargets.isEmpty()) {
            return false;
        }
        if (nativeQuery) {
            // Nothing narrows a native query, so a join in one is always the caller's to answer for.
            return true;
        }
        if (!tenantScopedEntities.contains(rootEntity(tokens))) {
            return true;
        }
        // A tenant-scoped root scopes what hangs off it through a foreign key, and nothing else.
        return joinTargets.stream().anyMatch(target -> !target.contains("."));
    }

    /** Lower-cased words and the punctuation that separates them, so keywords can be matched. */
    private static List<String> tokenize(String jpql) {
        List<String> tokens = new ArrayList<>();
        for (String raw : jpql.toLowerCase(Locale.ROOT).split("[\\s(),]+")) {
            if (!raw.isBlank()) {
                tokens.add(raw);
            }
        }
        return tokens;
    }

    /**
     * The target of every join that is not a fetch join, in order. A target containing a dot is an
     * association reached from an alias; one without is an entity named outright.
     */
    private static List<String> nonFetchJoinTargets(List<String> tokens) {
        List<String> targets = new ArrayList<>();
        for (int i = 0; i < tokens.size() - 1; i++) {
            if (!"join".equals(tokens.get(i))) {
                continue;
            }
            String next = tokens.get(i + 1);
            if ("fetch".equals(next)) {
                continue;
            }
            targets.add(next);
        }
        return targets;
    }

    /**
     * The entity the outermost query selects from, lower-cased. The first {@code from} is the
     * outer one in every query here; a subquery's root cannot precede it.
     */
    private static String rootEntity(List<String> tokens) {
        int from = tokens.indexOf("from");
        if (from < 0 || from + 1 >= tokens.size()) {
            return "";
        }
        return tokens.get(from + 1);
    }
}
