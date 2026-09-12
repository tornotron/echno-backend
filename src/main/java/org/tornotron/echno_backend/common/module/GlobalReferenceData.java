package org.tornotron.echno_backend.common.module;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a module entity is shared reference data with no owning organization, so its
 * missing tenant scope is a decision rather than a leak.
 *
 * <p>The module entity rule requires every {@code @Entity} under {@code modules..} to be tenant
 * scoped or to be an owned child of one that is. Seed tables such as a starter checklist or a
 * rule catalogue are neither: every organization reads the same rows. This marker is how such an
 * entity says so, and the reason is required so the next reader can judge whether it still holds.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface GlobalReferenceData {

    /** Why every organization may read these rows. */
    String value();
}
