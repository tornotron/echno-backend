package org.tornotron.echno_backend.modules.documentingestion.gate;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Puts a controller behind {@link DocumentIngestionGateInterceptor}. Every controller of the module
 * carries it, and {@code DocumentIngestionGateRuleTest} fails the build on one that does not.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DocumentIngestionGate {
}
