/**
 * The module SPI and its registry: the one piece of core that knows a "module" exists.
 *
 * <p>A module is a Spring component set under {@code org.tornotron.echno_backend.modules.<id>}
 * that is discovered by the ordinary component scan and described by exactly one
 * {@link org.tornotron.echno_backend.common.module.EchnoModule} bean. Core never names a module;
 * it collects every manifest bean into the
 * {@link org.tornotron.echno_backend.common.module.ModuleRegistry}, validates the declared
 * dependencies at boot, and answers two questions at runtime: what is installed, and what is
 * enabled for a given organization.
 *
 * <p>Two switches decide the second question, and both are read here so a module never has to
 * implement either. The operator kill switch {@code echno.modules.<id>.enabled} (default
 * {@code true}) turns a module off globally without a redeploy. Per-organization entitlement is
 * asked of the {@link org.tornotron.echno_backend.common.module.ModuleEntitlementResolver}, whose
 * default implementation says yes to everything and gives way to the billing implementation as
 * soon as one is on the context.
 *
 * <p>The boundary between core and a module's internals is enforced by the module ArchUnit rules
 * in the test tree rather than by a framework: core may reach a module only through its
 * {@code api} subpackage, every module entity is tenant-scoped or explicitly marked
 * {@link org.tornotron.echno_backend.common.module.GlobalReferenceData}, and every module package
 * carries exactly one manifest bean.
 */
package org.tornotron.echno_backend.common.module;
