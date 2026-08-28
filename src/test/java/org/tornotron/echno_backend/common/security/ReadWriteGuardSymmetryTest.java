package org.tornotron.echno_backend.common.security;

import org.junit.jupiter.api.BeforeEach;
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

import java.util.List;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.tornotron.echno_backend.common.payload.JsonPartBinder;
import org.tornotron.echno_backend.common.payload.PayloadValidator;

/**
 * Locks in read/write guard symmetry across the controllers that carried the asymmetry fixed
 * for projects in #399: their reads gated on tenant membership alone while their writes gated
 * on the system-admin or project-manager org role. A caller holding that role without a
 * recorded ORG_MEMBER_ authority, the bootstrap admin being the known example, could therefore
 * write these resources but got 403 reading them.
 *
 * <p>Every read below must accept either branch, membership or the elevated role. The
 * role-without-membership case is the one that was broken; if any of these read guards is
 * narrowed back to membership alone, that parameterized case fails for the offending path.
 *
 * <p>Deliberately one @WebMvcTest over all nine controllers rather than nine separate slices.
 * Spring caches a context per distinct slice and the test JVM is capped at 1024m, so nine new
 * contexts would be a real cost; this adds exactly one. @orgSecurity is mocked so each branch
 * is exercised without building JWT authorities.
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
        when(assetService.getAllAssets()).thenReturn(List.of());
        when(expenseService.getAll()).thenReturn(List.of());
        when(receiptService.getAll()).thenReturn(List.of());
        when(subContractService.getAll()).thenReturn(List.of());
        when(stockAdjustmentService.getAll()).thenReturn(List.of());
        when(issueService.getAllIssues()).thenReturn(List.of());
        when(categoryService.getAllCategories(anyInt(), anyInt())).thenReturn(Page.empty());
        when(taskService.getAllTasks(anyInt(), anyInt())).thenReturn(Page.empty());
        when(issueCommentService.getAllIssueComments(anyInt(), anyInt())).thenReturn(Page.empty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("widenedReadEndpoints")
    void read_isOk_forARoleHolderThatIsNotRecordedAsAMember(String path) throws Exception {
        // The case broken before this change: writes were allowed, reads were 403.
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(false);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "project-manager")).thenReturn(true);

        mockMvc.perform(get(path).with(jwt()))
                .andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("widenedReadEndpoints")
    void read_isOk_forAMemberWithoutAnElevatedRole(String path) throws Exception {
        // The widening must not cost plain members their existing access.
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "project-manager")).thenReturn(false);

        mockMvc.perform(get(path).with(jwt()))
                .andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("widenedReadEndpoints")
    void read_isForbidden_forACallerWithNeitherMembershipNorRole(String path) throws Exception {
        // Both branches are tenant-scoped, so an outsider is still refused. This is what keeps
        // the widening from becoming "any authenticated caller may read".
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(false);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "project-manager")).thenReturn(false);

        mockMvc.perform(get(path).with(jwt()))
                .andExpect(status().isForbidden());
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
