package org.tornotron.echno_backend.common.customAnnotation;

import java.lang.annotation.*;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireSubscription {

    /**
     * Feature code required to access this endpoint
     */
    String feature();

    /**
     * Optional: Record usage when accessing this endpoint
     */
    boolean recordUsage() default false;

    /**
     * Optional: Usage amount to record (default 1)
     */
    long usageAmount() default 1;

    /**
     * Optional: Custom error message
     */
    String errorMessage() default  "";
}
