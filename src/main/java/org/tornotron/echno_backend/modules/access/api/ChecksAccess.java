package org.tornotron.echno_backend.modules.access.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The permissions a bean checks in its own code, through {@link CallerAccess} or an area's access
 * bean, rather than in a {@code @access.can} guard. The Roles screen offers a permission only when
 * something checks it, and it learns that from the guards and from this list.
 *
 * <pre>
 *   &#64;ChecksAccess("org-admin:approve-own-documents")
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ChecksAccess {

    /** Each permission as {@code area:action}. */
    String[] value();
}
