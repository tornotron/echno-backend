package org.tornotron.echno_backend.modules.bim;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * The module kill switch as a bean condition: {@code echno.modules.bim.enabled}, on unless
 * set to {@code false}. Stacked on the import poller so the operator switch stops background
 * ingestion as well as the endpoints.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = BimModuleEnabled.PROPERTY, havingValue = "true", matchIfMissing = true)
public @interface BimModuleEnabled {

    String PROPERTY = "echno.modules." + BimModule.ID + ".enabled";
}
