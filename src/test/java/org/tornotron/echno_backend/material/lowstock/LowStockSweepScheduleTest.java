package org.tornotron.echno_backend.material.lowstock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.tornotron.echno_backend.common.multitenancy.WithoutTenant;
import org.tornotron.echno_backend.compliance.sweep.ComplianceRuleSweep;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether the sweep is ever triggered, and under what conditions.
 *
 * <p>Separate from {@link LowStockSweepTest} on purpose, and the separation is the point.
 * Everything in that class calls {@code runPass()} by hand, which exercises the logic and says
 * nothing at all about the schedule: a job whose {@code @Scheduled} was deleted, or whose cron
 * expression does not parse, or whose bean never exists, would pass every one of those tests. The
 * trigger is a declaration rather than behaviour, so this reads the declaration.
 *
 * <p>What it does not claim: nothing here waits for a cron to fire. It asserts that the trigger
 * exists, that its default expression is one Spring can actually parse, that the bean is
 * conditional and off, and that a failing pass cannot take the schedule down with it.
 */
class LowStockSweepScheduleTest {

    private Method sweepMethod() throws NoSuchMethodException {
        return LowStockSweep.class.getMethod("sweep");
    }

    @Test
    @DisplayName("the pass is on a cron trigger, read from configuration")
    void isScheduled() throws Exception {
        Scheduled scheduled = sweepMethod().getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("${inventory.reorder-sweep.cron:0 30 3 * * *}");
        assertThat(scheduled.zone()).isEqualTo("${inventory.reorder-sweep.zone:UTC}");
    }

    @Test
    @DisplayName("the default cron expression is one Spring can parse")
    void defaultCronParses() {
        // A property placeholder that resolves to nonsense fails at context refresh, months after
        // the change that broke it, on whichever environment did not override the property.
        assertThat(CronExpression.isValidExpression(defaultCron())).isTrue();
    }

    @Test
    @DisplayName("the default window is not the compliance sweep's window")
    void doesNotLandOnTheComplianceSweep() throws Exception {
        // Both are nightly, both run against the same database on the same scheduler pool, and
        // development is deployed to two stagings that already carry the compliance pass. There
        // is no reason to put them on the same minute and no cost to not doing so.
        Scheduled compliance = ComplianceRuleSweep.class.getMethod("sweep")
                .getAnnotation(Scheduled.class);
        String complianceCron = defaultOf(compliance.cron());

        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime ours = CronExpression.parse(defaultCron()).next(from);
        LocalDateTime theirs = CronExpression.parse(complianceCron).next(from);

        assertThat(ours).isNotEqualTo(theirs);
    }

    @Test
    @DisplayName("the bean does not exist unless somebody turns it on")
    void isOffByDefault() {
        ConditionalOnProperty conditional =
                LowStockSweep.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(conditional).isNotNull();
        assertThat(conditional.name()).containsExactly("inventory.reorder-sweep.enabled");
        assertThat(conditional.havingValue()).isEqualTo("true");
        // matchIfMissing defaults to false, so an environment that says nothing gets no bean.
        assertThat(conditional.matchIfMissing()).isFalse();
        assertThat(new LowStockSweepProperties().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("the pass declares that it belongs to no organization, and says why")
    void declaresItsScope() throws Exception {
        // Both tenant-isolation mechanisms fail open on a missing organization id and
        // UnscopedAccessGuard warns rather than denies, so an undeclared scheduled job reads
        // every tenant's rows and looks healthy doing it. The declaration is what makes the
        // choice visible instead of accidental.
        WithoutTenant withoutTenant = sweepMethod().getAnnotation(WithoutTenant.class);

        assertThat(withoutTenant).isNotNull();
        assertThat(withoutTenant.value()).isNotBlank();
    }

    @Test
    @DisplayName("a failing pass does not stop the schedule")
    void survivesAFailingPass() {
        // Spring stops rescheduling a @Scheduled method that throws. A sweep that quietly stopped
        // sweeping after one bad night is worse than the gap it was built to close, because
        // nothing about the system looks any different afterwards.
        LowStockSweep sweep = new LowStockSweep(null, null, null, null, null, null, null, null,
                new LowStockSweepProperties());

        sweep.sweep();
    }

    private String defaultCron() {
        return defaultOf("${inventory.reorder-sweep.cron:0 30 3 * * *}");
    }

    /** The fallback out of a {@code ${property:default}} placeholder. */
    private String defaultOf(String placeholder) {
        return placeholder.substring(placeholder.indexOf(':') + 1, placeholder.length() - 1);
    }
}
