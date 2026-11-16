package org.tornotron.echno_backend.modules.assistant.time;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Marks the {@link java.time.Clock} the assistant reads "today" and "this week" from: the one
 * {@link AssistantClockConfiguration} builds in the sites' zone.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD, ElementType.TYPE})
public @interface AssistantClock {
}
