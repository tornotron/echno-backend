package org.tornotron.echno_backend.modules.access.web.dto;

import java.util.List;

/**
 * Everything the Roles &amp; Permissions screen needs to draw the matrix: its sections, its areas
 * with their actions, and the scopes a grant can carry.
 *
 * @param sections the section names, in display order
 * @param areas    the areas, in display order
 * @param scopes   the scopes a grant can carry: {@code ALL}, {@code ASSIGNED_PROJECTS},
 *                 {@code RELATED}, {@code OWN}
 */
public record AccessCatalogDto(List<String> sections, List<AreaDto> areas, List<String> scopes) {

    /**
     * One area of the matrix.
     *
     * @param id      the area id, the part before the colon in a permission
     * @param label   what the screen shows
     * @param section the section it is listed under
     * @param actions the actions it offers: view, create, edit, approve, delete, then any named
     *                ones such as certify
     */
    public record AreaDto(String id, String label, String section, List<String> actions) {
    }
}
