package org.tornotron.echno_backend.common.events.listeners;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.events.ProjectApprovedEvent;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.compliance.ComplianceGenerationService;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The approval path runs on a pooled async thread with no request behind it, so what
 * matters here is that the tenant is established from the event before any work happens,
 * and that the listener owns no transaction of its own.
 */
@ExtendWith(MockitoExtension.class)
class ComplianceGenerationListenerTest {

    private static final Long PROJECT_ID = 42L;
    private static final Long ORG_ID = 7L;

    @Mock
    private ComplianceGenerationService complianceGenerationService;
    @Mock
    private TenantScopedJobRunner tenantScopedJobRunner;

    @InjectMocks
    private ComplianceGenerationListener listener;

    /** Makes the mocked runner behave like the real one for the duration of a call. */
    private void runInline() {
        doAnswer(invocation -> {
            Long orgId = invocation.getArgument(0);
            TenantContext.setCurrentOrgId(orgId);
            try {
                invocation.getArgument(1, Runnable.class).run();
            } finally {
                TenantContext.clear();
            }
            return null;
        }).when(tenantScopedJobRunner).runForTenant(anyLong(), any(Runnable.class));
    }

    @Test
    void establishesTheTenantFromTheEventBeforeGenerating() {
        runInline();
        AtomicReference<Long> tenantDuringWork = new AtomicReference<>();
        when(complianceGenerationService.generateForProject(PROJECT_ID, ORG_ID))
                .thenAnswer(invocation -> {
                    tenantDuringWork.set(TenantContext.getCurrentOrgId());
                    return java.util.List.of();
                });

        listener.onProjectApproved(new ProjectApprovedEvent(this, PROJECT_ID, ORG_ID));

        verify(tenantScopedJobRunner).runForTenant(eq(ORG_ID), any(Runnable.class));
        assertThat(tenantDuringWork.get())
                .as("generation must run with the event's organization on the thread")
                .isEqualTo(ORG_ID);
    }

    @Test
    void eventWithNoOrganization_generatesNothing() {
        listener.onProjectApproved(new ProjectApprovedEvent(this, PROJECT_ID, null));

        verify(complianceGenerationService, never()).generateForProject(anyLong(), any());
        verify(tenantScopedJobRunner, never()).runForTenant(any(), any(Runnable.class));
    }

    @Test
    void unmetPrecondition_isSwallowedSoApprovalIsNotDisturbed() {
        runInline();
        when(complianceGenerationService.generateForProject(PROJECT_ID, ORG_ID))
                .thenThrow(new InvalidRequestException("no rules for this jurisdiction yet"));

        assertThatCode(() -> listener.onProjectApproved(new ProjectApprovedEvent(this, PROJECT_ID, ORG_ID)))
                .doesNotThrowAnyException();
    }

    @Test
    @MockitoSettings(strictness = Strictness.LENIENT)
    void listenerOwnsNoTransaction() throws Exception {
        Method method = ComplianceGenerationListener.class
                .getMethod("onProjectApproved", ProjectApprovedEvent.class);

        assertThat(method.getAnnotation(Transactional.class))
                .as("""
                        A @Transactional here re-creates the defect this listener was fixed for. \
                        The transaction interceptor is pinned at HIGHEST_PRECEDENCE and \
                        HibernateFilterConfig at LOWEST_PRECEDENCE, so the filter aspect reads the \
                        tenant context before the body can set it, sees null, and never enables \
                        orgFilter for the transaction. It would also hold a pool connection across \
                        the whole external model call.""")
                .isNull();
    }
}
