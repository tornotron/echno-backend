/**
 * Planted module trees for {@link org.tornotron.echno_backend.architecture.ModuleBoundaryRuleTest}:
 * {@code clean} obeys every module rule and {@code violating} breaks each one once.
 *
 * <p>This tree sits outside {@code org.tornotron.echno_backend} on purpose. The fixtures carry
 * real {@code @Entity} and {@code @Component} annotations, because the rules key on them, and
 * anything so annotated under the application's package would be picked up by the entity scan
 * and the component scan of every {@code @SpringBootTest} in the suite; with
 * {@code ddl-auto=validate} an entity with no table fails the boot. Out here nothing scans them.
 * {@code ModuleBoundaryRuleTest} asserts that placement so it cannot drift.
 */
package org.tornotron.echno_modulefixtures;
