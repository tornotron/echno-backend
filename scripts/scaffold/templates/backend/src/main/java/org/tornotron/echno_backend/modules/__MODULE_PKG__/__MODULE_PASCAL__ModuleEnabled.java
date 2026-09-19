package org.tornotron.echno_backend.modules.__MODULE_PKG__;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * The module kill switch as a bean condition: {@code echno.modules.__MODULE_ID__.enabled}, on
 * unless set to {@code false}. Stack it on any scheduled job or listener the module adds so
 * the operator switch stops background work as well as the endpoints.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = __MODULE_PASCAL__ModuleEnabled.PROPERTY, havingValue = "true", matchIfMissing = true)
public @interface __MODULE_PASCAL__ModuleEnabled {

    String PROPERTY = "echno.modules." + __MODULE_PASCAL__Module.ID + ".enabled";
}
