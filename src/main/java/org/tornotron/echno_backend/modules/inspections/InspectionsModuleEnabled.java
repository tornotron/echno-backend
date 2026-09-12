package org.tornotron.echno_backend.modules.inspections;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * The module kill switch as a bean condition: {@code echno.modules.inspections.enabled}, on
 * unless set to {@code false}.
 *
 * <p>The registry reads the same property for every request-time answer, but a scheduled job
 * is not a request. Stacking this on a job's own {@code @ConditionalOnProperty} means the
 * operator switch stops the sweep and the dispatcher as well as the endpoints, while the older
 * {@code compliance.sweep.enabled} and {@code compliance.job.enabled} properties keep their
 * meaning. Spring evaluates every conditional annotation on a class, so the two combine as an
 * AND without either having to know about the other.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = InspectionsModuleEnabled.PROPERTY, havingValue = "true", matchIfMissing = true)
public @interface InspectionsModuleEnabled {

    String PROPERTY = "echno.modules." + InspectionsModule.ID + ".enabled";
}
