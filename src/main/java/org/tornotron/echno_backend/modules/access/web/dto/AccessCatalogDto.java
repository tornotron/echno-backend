package org.tornotron.echno_backend.modules.access.web.dto;

import java.util.List;
import org.tornotron.echno_backend.modules.access.matrix.catalog.AccessCatalogReader.AreaEntry;

/**
 * Everything the Roles &amp; Permissions screen needs to draw the matrix: its sections, its areas
 * with their actions, and the scopes a grant can carry.
 *
 * <p>Each action says whether a guard enforces it. The screen offers a choice only for an enforced
 * action and shows the rest as not yet controlled here.
 *
 * @param sections the section names, in display order
 * @param areas    the areas, in display order, each with its actions and their enforced flags
 * @param scopes   the scopes a grant can carry: {@code ALL}, {@code ASSIGNED_PROJECTS},
 *                 {@code RELATED}, {@code OWN}
 */
public record AccessCatalogDto(List<String> sections, List<AreaEntry> areas, List<String> scopes) {
}
