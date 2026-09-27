package org.tornotron.echno_backend.modules.toolboxtalks.time;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Marks the {@link java.time.Clock} the Toolbox Talks module reads "today" from: the one
 * {@link ToolboxTalksClockConfiguration} builds in the module's zone.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD, ElementType.TYPE})
public @interface ToolboxTalksClock {
}
