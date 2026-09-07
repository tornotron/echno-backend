package org.tornotron.echno_backend.material.lowstock;

/**
 * One project that holds stock, and the organization it belongs to.
 *
 * <p>Two scalars rather than the entities, because the sweep that reads this runs across every
 * tenant at once and must not load a tenant-scoped row while it is doing so. It reads the pair,
 * establishes the tenant from the organization id, and only then reads anything belonging to it.
 *
 * @param organizationId The tenant the project belongs to.
 * @param projectId The project.
 */
public record StockedProject(Long organizationId, Long projectId) {
}
