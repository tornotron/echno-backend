package org.tornotron.echno_backend.common.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registers a bean only when Echno sends email itself, that is when {@code echno.mail.provider} is set
 * to anything other than {@code none}. It is never the default.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnExpression("'${echno.mail.provider:none}'.trim() != '' and '${echno.mail.provider:none}'.trim() != 'none'")
public @interface ConditionalOnMailSender {
}
