/**
 * Home of the pluggable modules. Each module lives in its own subpackage
 * {@code org.tornotron.echno_backend.modules.<id>}, is discovered by the ordinary component scan,
 * and declares exactly one {@link org.tornotron.echno_backend.common.module.EchnoModule} bean.
 *
 * <p>The subpackage is a boundary, checked by the module ArchUnit rules: code outside a module may
 * reach it only through its {@code api} subpackage, one module may reach another only the same
 * way, and every {@code @Entity} here is tenant scoped, an owned child of a tenant-scoped
 * entity, or marked {@link org.tornotron.echno_backend.common.module.GlobalReferenceData}.
 *
 * <p>Empty until the first module is carved out; the rules already run against it.
 */
package org.tornotron.echno_backend.modules;
