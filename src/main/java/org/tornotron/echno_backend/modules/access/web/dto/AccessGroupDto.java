package org.tornotron.echno_backend.modules.access.web.dto;

import java.util.List;
import java.util.UUID;

/**
 * One department or group as the Roles &amp; Permissions screen shows it.
 *
 * @param id          the group's id
 * @param kind        {@code DEPARTMENT} or {@code GROUP}
 * @param name        what the screen shows
 * @param description what the group is for, or null
 * @param memberIds   the employees in it
 * @param roleIds     the roles its members hold through it
 * @param grants      what it grants its members directly; a group's grants are never locked
 */
public record AccessGroupDto(
        UUID id,
        String kind,
        String name,
        String description,
        List<Long> memberIds,
        List<UUID> roleIds,
        List<AccessGrantDto> grants) {
}
