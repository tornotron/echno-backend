package org.tornotron.echno_backend.modules.assistant;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * The module kill switch as a bean condition: {@code echno.modules.assistant.enabled}, off
 * unless set to {@code true}. Stack it on any scheduled job, listener, or controller the module
 * adds so it stays dark until an operator turns it on (unlike the modules that default to on;
 * see {@link AssistantModule}).
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = AssistantModuleEnabled.PROPERTY, havingValue = "true")
public @interface AssistantModuleEnabled {

    String PROPERTY = "echno.modules." + AssistantModule.ID + ".enabled";
}
