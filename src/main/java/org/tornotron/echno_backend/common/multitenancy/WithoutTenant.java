package org.tornotron.echno_backend.common.multitenancy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that the annotated method belongs to no organization, so reaching tenant-scoped data
 * without an organization id is a decision here rather than a mistake.
 *
 * <p>Use it on work that genuinely runs outside every tenant: startup and bootstrap, a fixture
 * provisioner, a pre-tenant endpoint. Do not use it to quieten a warning on work that does have
 * a tenant. That work wants {@link TenantScopedJobRunner}, which pins the organization the job
 * belongs to and refuses to run without one.
 *
 * <p>This is deliberately weaker than {@code @BypassTenantFilter}. Bypass turns tenant isolation
 * off outright, including where an organization id is present, which is why it is audited at WARN
 * where it is set. This only suppresses the missing-scope failure: if an organization id happens
 * to be in force when the method runs, the {@code orgFilter} and the load-boundary check stay
 * fully in force. So putting this on a method that is also called inside a tenant request cannot
 * open that request up, which is the property that makes it safe to apply to shared service code.
 *
 * <p>The reason is required and is recorded on the audit line, because a bare annotation a year
 * from now says only that somebody wanted the check off, not whether they were right.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface WithoutTenant {

    /** Why this work has no organization. Recorded with the declaration. */
    String value();
}
