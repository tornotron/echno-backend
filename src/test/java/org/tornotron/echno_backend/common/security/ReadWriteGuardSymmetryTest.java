package org.tornotron.echno_backend.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.tornotron.echno_backend.IssueComment.IssueCommentControllerWeb;
import org.tornotron.echno_backend.IssueComment.IssueCommentService;
import org.tornotron.echno_backend.asset.AssetControllerWeb;
import org.tornotron.echno_backend.asset.AssetService;
import org.tornotron.echno_backend.category.CategoryControllerWeb;
import org.tornotron.echno_backend.category.CategoryService;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.expense.ExpenseControllerWeb;
import org.tornotron.echno_backend.expense.ExpenseService;
import org.tornotron.echno_backend.issue.IssueControllerWeb;
import org.tornotron.echno_backend.issue.IssueService;
import org.tornotron.echno_backend.receipt.ReceiptControllerWeb;
import org.tornotron.echno_backend.receipt.ReceiptService;
import org.tornotron.echno_backend.stockAdjustment.StockAdjustmentControllerWeb;
import org.tornotron.echno_backend.stockAdjustment.StockAdjustmentService;
import org.tornotron.echno_backend.subcontract.SubContractControllerWeb;
import org.tornotron.echno_backend.subcontract.SubContractService;
import org.tornotron.echno_backend.task.TaskControllerWeb;
import org.tornotron.echno_backend.task.TaskService;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.tornotron.echno_backend.common.payload.JsonPartBinder;
import org.tornotron.echno_backend.common.payload.PayloadValidator;

/**
 * Locks in the read guards across the nine controllers that carried the asymmetry fixed for
 * projects in #399: their reads gated on tenant membership while their writes gated on the
 * system-admin or project-manager org role.
 *
 * <p><b>The role-without-membership case this class used to assert has been removed, because it
 * asserted an outcome for a state the real beans cannot produce.</b> It stubbed
 * {@code isMemberOfCurrentTenant()} false and {@code hasAnyOrgRoleForCurrentTenant(...)} true.
 * The real {@code OrganizationSecurityService} answers both from
 * {@code TenantContext.getCurrentOrgId()} and refuses a null one, and {@code TenantFilter} sets
 * that only after confirming an {@code ORG_MEMBER_} authority, so a non-member has no
 * organization in force and the role check returns false as well. The pair could never occur, and
 * the test passed on the stub rather than on anything the application does. It is the same
 * mistake as {@code eec6c37}, which shipped a guard clause that decided nothing with a green
 * test over it, and #709 and #717 then spent two issues establishing that the clause was dead.
 *
 * <p>{@code TenantFilterTest.theRoleGuardCannotSucceedWhereTheMembershipGuardFails} is where that
 * claim belongs, and it is made there against the real {@code OrganizationSecurityService} inside
 * the real filter chain. The split it depended on is now prevented outright, in
 * {@code KeycloakGroupService} and {@code KeycloakInitializer}, and pinned by
 * {@code KeycloakOrgMembershipInvariantIT} against a real Keycloak.
 *
 * <p>So that the mistake is harder to repeat here than to catch by review,
 * {@link #stubTenantGuards} refuses the impossible pair rather than stubbing it. Mocking
 * {@code @orgSecurity} is still the right call for a slice, since it exercises the guard
 * expression without building JWT authorities; what was wrong was stubbing a combination the
 * bean cannot return, and that is now a test failure at the point of stubbing.
 *
 * <p>Deliberately one @WebMvcTest over all nine controllers rather than nine separate slices.
 * Spring caches a context per distinct slice and the test JVM is capped, so nine new contexts
 * would be a real cost; this adds exactly one.
 */
@WebMvcTest({
        AssetControllerWeb.class,
        ExpenseControllerWeb.class,
        ReceiptControllerWeb.class,
        SubContractControllerWeb.class,
        StockAdjustmentControllerWeb.class,
        CategoryControllerWeb.class,
        TaskControllerWeb.class,
        IssueControllerWeb.class,
        IssueCommentControllerWeb.class
})
@Import({ReadWriteGuardSymmetryTest.TestSecurityConfig.class, JsonPartBinder.class, PayloadValidator.class})
class ReadWriteGuardSymmetryTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private AssetService assetService;
    @MockitoBean private ExpenseService expenseService;
    @MockitoBean private ReceiptService receiptService;
    @MockitoBean private SubContractService subContractService;
    @MockitoBean private StockAdjustmentService stockAdjustmentService;
    @MockitoBean private CategoryService categoryService;
    @MockitoBean private TaskService taskService;
    @MockitoBean private IssueService issueService;
    @MockitoBean private IssueCommentService issueCommentService;

    // Named to match the @orgSecurity bean the @PreAuthorize SpEL references.
    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    // Satisfies RPTExchangeFilter, a custom filter the web slice loads; unused here
    // because .with(jwt(...)) sets the authentication directly.
    @MockitoBean private KeycloakAuthorizationService keycloakAuthorizationService;

    // RPTExchangeFilter also depends on this cache; mocked for the same reason.
    @MockitoBean private RPTCache rptCache;

    /** The list endpoint of each controller whose read guard was widened. */
    static Stream<String> widenedReadEndpoints() {
        return Stream.of(
                "/api/v1/assets/web",
                "/api/v1/expenses/web",
                "/api/v1/receipts/web",
                "/api/v1/sub-contracts/web",
                "/api/v1/stock-adjustments/web",
                "/api/v1/category/web",
                "/api/v1/tasks/web",
                "/api/v1/issues/web",
                "/api/v1/issues/comments/web"
        );
    }

    @BeforeEach
    void stubEmptyResults() {
        // The handlers that page call getContent() on the result, so these cannot be left null.
        when(assetService.getAllAssets(anyInt(), anyInt())).thenReturn(Page.empty());
        when(expenseService.getPaginated(anyInt(), anyInt(), any(), any())).thenReturn(Page.empty());
        when(receiptService.getPaginated(anyInt(), anyInt(), any(), any())).thenReturn(Page.empty());
        when(subContractService.getPaginated(anyInt(), anyInt(), any(), any(), any())).thenReturn(Page.empty());
        when(stockAdjustmentService.getAll(anyInt(), anyInt())).thenReturn(Page.empty());
        when(issueService.getAllIssuesPaginated(anyInt(), anyInt(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Page.empty());
        when(categoryService.getAllCategories(anyInt(), anyInt())).thenReturn(Page.empty());
        when(taskService.getAllTasks(anyInt(), anyInt())).thenReturn(Page.empty());
        when(issueCommentService.getAllIssueComments(anyInt(), anyInt())).thenReturn(Page.empty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("widenedReadEndpoints")
    void read_isOk_forAMemberWithoutAnElevatedRole(String path) throws Exception {
        // A plain member of the tenant reads these, which is what the guards decide on.
        stubTenantGuards(true, false);

        mockMvc.perform(get(path).with(jwt()))
                .andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("widenedReadEndpoints")
    void read_isOk_forAMemberHoldingAnElevatedRole(String path) throws Exception {
        // An admin reads them as well, by being a member. Worth stating separately: it is the
        // case the removed test was reaching for, and it is satisfied without the role deciding
        // anything, because a role holder is a member.
        stubTenantGuards(true, true);

        mockMvc.perform(get(path).with(jwt()))
                .andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("widenedReadEndpoints")
    void read_isForbidden_forACallerWithNeitherMembershipNorRole(String path) throws Exception {
        // A caller with no relationship to the tenant is refused. The guards are tenant-scoped,
        // so this is what keeps them from reading "any authenticated caller may read".
        stubTenantGuards(false, false);

        mockMvc.perform(get(path).with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void stubbingARoleWithoutMembershipIsRefusedAsAStateTheBeansCannotProduce() {
        // The guard on the helper, tested so it cannot quietly stop guarding. Without it, the
        // one stub combination that proves nothing is also the easiest one to write.
        assertThatThrownBy(() -> stubTenantGuards(false, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot hold an org role");
    }

    /**
     * Stubs the two tenant guards together, refusing the pair the real beans cannot return.
     *
     * <p>{@code hasAnyOrgRoleForCurrentTenant} implies {@code isMemberOfCurrentTenant}: both read
     * {@code TenantContext.getCurrentOrgId()} and refuse a null one, and the tenant is only
     * resolved for a member. A test that stubs a role without membership is asserting behaviour
     * for a request that cannot reach a controller, so it passes whatever the guard says.
     *
     * @param member whether the caller is a member of the tenant in force
     * @param role   whether the caller holds one of the elevated roles in it
     */
    private void stubTenantGuards(boolean member, boolean role) {
        if (role && !member) {
            throw new IllegalArgumentException(
                    "A caller cannot hold an org role for the current tenant without being a member "
                            + "of it: both checks read TenantContext.getCurrentOrgId(), and TenantFilter "
                            + "sets it only after confirming an ORG_MEMBER_ authority. Stubbing this pair "
                            + "asserts an outcome for a request that cannot reach a controller. See "
                            + "TenantFilterTest.theRoleGuardCannotSucceedWhereTheMembershipGuardFails.");
        }
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(member);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "project-manager")).thenReturn(role);
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
            return http.build();
        }
    }
}
