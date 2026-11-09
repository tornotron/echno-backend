package org.tornotron.echno_backend.modules.documentingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.modules.access.matrix.AccessFixtures;
import org.tornotron.echno_backend.modules.access.matrix.service.AccessRoleReconciler;
import org.tornotron.echno_backend.organization.Organization;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.context.event.EventListener;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkReader;
import org.tornotron.echno_backend.modules.documentingestion.job.ExtractJobWorker;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * The property of section 6.7 of the design, on a full context in {@code advisory} mode with an
 * organization that holds no subscription: with no module configuration set, every endpoint of the
 * module refuses with 403 {@code MODULE_DISABLED}, the module is absent from
 * {@code /api/v1/modules/web/enabled}, no poller or listener bean exists, and nothing is written.
 *
 * <p>The endpoints are read from the handler mapping and the tables from the catalog, so a later
 * endpoint or table is covered with no change here. {@link DocumentIngestionEmptyAllowListIT} runs
 * the same checks with the switch on.
 */
@ActiveProfiles("local")
@SpringBootTest(properties = {
        "keycloak-initializer.initialize-on-startup=false",
        "echno.cache.provider=caffeine",
        "echno.entitlement.mode=advisory"
})
@AutoConfigureMockMvc
class DocumentIngestionOffByDefaultIT extends AbstractIntegrationTest {

    private static final String MODULE_PACKAGE = DocumentIngestionModule.class.getPackageName();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    jakarta.persistence.EntityManager em;

    @Autowired
    AccessRoleReconciler reconciler;

    Long organizationId;

    /** The member's sign-in subject; the enabled-modules list is asked of the matrix, which needs an employee. */
    String memberSubject;

    @BeforeEach
    void anOrganizationWithNoSubscription() {
        String name = "Ingestion off " + UUID.randomUUID();
        organizationId = jdbc.queryForObject(
                "INSERT INTO organization (organization_name, organization_address, organization_email, "
                        + "organization_phone) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, name, name, UUID.randomUUID() + "@example.test", "0000000000");
        String member = "ingestion-member-" + UUID.randomUUID();
        memberSubject = "kc-" + member;
        transactions.executeWithoutResult(status -> {
            new AccessFixtures(em).employee(em.find(Organization.class, organizationId), member);
            reconciler.ensureStartingRoles(organizationId);
        });
    }

    RequestPostProcessor member() {
        return jwt().jwt(token -> token.subject(memberSubject))
                .authorities(new SimpleGrantedAuthority("ORG_MEMBER_" + organizationId));
    }

    @Test
    void everyEndpointRefusesWithTheStableCodeEvenWithNoBody() throws Exception {
        List<String> tried = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            if (!entry.getValue().getBeanType().getPackageName().startsWith(MODULE_PACKAGE)) {
                continue;
            }
            for (String pattern : entry.getKey().getPatternValues()) {
                String path = pattern.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
                for (RequestMethod method : entry.getKey().getMethodsCondition().getMethods()) {
                    tried.add(method + " " + pattern);
                    mockMvc.perform(request(HttpMethod.valueOf(method.name()), path).with(member()))
                            .andExpect(status().isForbidden())
                            .andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
                }
            }
        }
        assertThat(tried).as("the module has endpoints to refuse").isNotEmpty();
    }

    @Test
    void theModuleIsAbsentFromTheEnabledList() throws Exception {
        mockMvc.perform(get("/api/v1/modules/web/enabled").with(member()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'document-ingestion')]").isEmpty());
    }

    @Test
    void nothingIsWrittenByAnyRefusedRequest() throws Exception {
        everyEndpointRefusesWithTheStableCodeEvenWithNoBody();

        for (String table : jdbc.queryForList("SELECT table_name FROM information_schema.tables "
                + "WHERE table_name LIKE 'doc\\_ingest\\_%'", String.class)) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class))
                    .as("rows in %s", table).isZero();
        }
    }

    @Test
    void noPollerOrListenerBeanExists() {
        assertThat(moduleBeansThatRunWithoutARequest()).isEmpty();
        assertThat(context.getBeanNamesForType(ChunkReader.class)).as("no bean that can send an LLM call").isEmpty();
        assertThat(context.getBeanNamesForType(ExtractJobWorker.class)).isEmpty();
    }

    /** Beans of this module holding a scheduled method or an event listener. */
    List<String> moduleBeansThatRunWithoutARequest() {
        List<String> found = new ArrayList<>();
        for (String name : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(name);
            if (type == null || !type.getName().startsWith(MODULE_PACKAGE)) {
                continue;
            }
            ReflectionUtils.doWithMethods(type, method -> {
                if (AnnotatedElementUtils.hasAnnotation(method, Scheduled.class)
                        || AnnotatedElementUtils.hasAnnotation(method, EventListener.class)
                        || AnnotatedElementUtils.hasAnnotation(method, TransactionalEventListener.class)) {
                    found.add(name + "." + method.getName());
                }
            });
        }
        return found;
    }
}
