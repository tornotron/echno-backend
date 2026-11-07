package org.tornotron.echno_backend.common.identity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registers a bean only when Keycloak is the identity provider, which is also what an environment
 * that sets nothing gets.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = "echno.identity.provider", havingValue = "keycloak", matchIfMissing = true)
public @interface ConditionalOnKeycloak {
}
