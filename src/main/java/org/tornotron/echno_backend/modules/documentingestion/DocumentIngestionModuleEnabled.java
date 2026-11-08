package org.tornotron.echno_backend.modules.documentingestion;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * The operator switch as a bean condition, for everything that runs without a request: pollers,
 * listeners and scheduled jobs. It has no {@code matchIfMissing}, unlike the other modules, so a
 * missing property means the bean does not exist. Controllers do not carry this; they stay
 * mapped and refuse with 403 {@code MODULE_DISABLED}, which keeps the OpenAPI document one shape.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = DocumentIngestionModuleEnabled.PROPERTY, havingValue = "true")
public @interface DocumentIngestionModuleEnabled {

    String PROPERTY = "echno.modules." + DocumentIngestionModule.ID + ".enabled";
}
