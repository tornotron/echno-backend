package org.tornotron.echno_backend.modules.toolboxtalks;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * The module kill switch as a bean condition: {@code echno.modules.toolbox-talks.enabled}, on
 * unless set to {@code false}. Stack it on any scheduled job or listener the module adds so
 * the operator switch stops background work as well as the endpoints.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = ToolboxTalksModuleEnabled.PROPERTY, havingValue = "true", matchIfMissing = true)
public @interface ToolboxTalksModuleEnabled {

    String PROPERTY = "echno.modules." + ToolboxTalksModule.ID + ".enabled";
}
