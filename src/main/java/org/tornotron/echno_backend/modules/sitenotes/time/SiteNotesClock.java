package org.tornotron.echno_backend.modules.sitenotes.time;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Marks the {@link java.time.Clock} the Site Notes module reads "today" from: the one
 * {@link SiteNotesClockConfiguration} builds in the module's zone.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD, ElementType.TYPE})
public @interface SiteNotesClock {
}
