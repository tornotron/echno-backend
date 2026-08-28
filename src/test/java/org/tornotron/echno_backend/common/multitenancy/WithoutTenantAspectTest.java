package org.tornotron.echno_backend.common.multitenancy;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The aspect behind {@link WithoutTenant}, and the ordering invariant it depends on.
 */
class WithoutTenantAspectTest {

    private final WithoutTenantAspect aspect = new WithoutTenantAspect();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    /** Carries a real annotation instance, which is easier to obtain than to synthesize. */
    @SuppressWarnings("unused")
    private static class Sample {
        @WithoutTenant("startup, before any organization exists")
        void annotated() {
        }
    }

    private static WithoutTenant annotation() throws NoSuchMethodException {
        return Sample.class.getDeclaredMethod("annotated").getAnnotation(WithoutTenant.class);
    }

    private ProceedingJoinPoint joinPointThat(Runnable body) throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            body.run();
            return "done";
        });
        return joinPoint;
    }

    @Test
    void theReasonIsVisibleWhileTheMethodRuns() throws Throwable {
        String[] seen = new String[1];

        Object result = aspect.declareUnscoped(
                joinPointThat(() -> seen[0] = TenantContext.getUnscopedReason()), annotation());

        assertThat(result).isEqualTo("done");
        assertThat(seen[0]).isEqualTo("startup, before any organization exists");
    }

    @Test
    void theDeclarationIsWithdrawnAfterwards() throws Throwable {
        aspect.declareUnscoped(joinPointThat(() -> {}), annotation());

        assertThat(TenantContext.isUnscopedDeclared()).isFalse();
    }

    @Test
    void theDeclarationIsWithdrawnWhenTheMethodThrows() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("boom"));

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> aspect.declareUnscoped(joinPoint, annotation()));

        assertThat(TenantContext.isUnscopedDeclared()).isFalse();
    }

    @Test
    void anEnclosingDeclarationIsRestoredRatherThanDiscarded() throws Throwable {
        TenantContext.declareUnscoped("the caller's own reason");

        aspect.declareUnscoped(joinPointThat(() -> {}), annotation());

        assertThat(TenantContext.getUnscopedReason()).isEqualTo("the caller's own reason");
    }

    @Test
    void anOrganizationIdInForceIsLeftUntouched() throws Throwable {
        // This is what separates the annotation from @BypassTenantFilter. A method carrying it
        // may also be called from inside a tenant request, and there it must not widen anything.
        TenantContext.setCurrentOrgId(42L);
        Long[] seen = new Long[1];

        aspect.declareUnscoped(joinPointThat(() -> seen[0] = TenantContext.getCurrentOrgId()), annotation());

        assertThat(seen[0]).isEqualTo(42L);
        assertThat(TenantContext.getCurrentOrgId()).isEqualTo(42L);
    }

    @Test
    void theScopeDeclaringAspectsRunAheadOfTheFilterAspect() {
        // HibernateFilterConfig reads the scope on entry. An aspect that declares the scope at
        // the same order might run after it, and the declaration would arrive too late to be
        // seen, which is the ordering accident #508 was one aspect over. Lower order is outer.
        int filterOrder = HibernateFilterConfig.class.getAnnotation(Order.class).value();
        int bypassOrder = TenantFilterBypassAspect.class.getAnnotation(Order.class).value();

        assertThat(WithoutTenantAspect.ORDER)
                .as("@WithoutTenant must be applied before HibernateFilterConfig reads the scope")
                .isLessThan(filterOrder);
        assertThat(bypassOrder)
                .as("@BypassTenantFilter must be applied before HibernateFilterConfig reads the scope")
                .isLessThan(filterOrder);
    }
}
