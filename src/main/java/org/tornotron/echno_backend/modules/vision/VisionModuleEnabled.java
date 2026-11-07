package org.tornotron.echno_backend.modules.vision;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * The module kill switch as a bean condition: {@code echno.modules.vision.enabled}, off unless set
 * to {@code true}. Every bean of the module except the manifest carries it, controllers included,
 * so that with the switch off no endpoint, listener or intake check exists. This is the opposite
 * default to the other modules on purpose; see {@link VisionModule}.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = VisionModuleEnabled.PROPERTY, havingValue = "true")
public @interface VisionModuleEnabled {

    String PROPERTY = "echno.modules." + VisionModule.ID + ".enabled";
}
