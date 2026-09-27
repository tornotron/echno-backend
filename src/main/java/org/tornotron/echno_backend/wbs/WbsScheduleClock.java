package org.tornotron.echno_backend.wbs;

import org.springframework.beans.factory.annotation.Qualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the {@link java.time.Clock} the schedule reads "today" from when it works out a delay:
 * the one {@link WbsScheduleClockConfiguration} builds in the sites' zone.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD, ElementType.TYPE})
public @interface WbsScheduleClock {
}
