package org.tornotron.echno_backend.modules.access.web.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * The whole set of a group's members; it replaces the current set.
 *
 * @param employeeIds the employees; empty leaves the group with nobody in it
 */
public record AccessMembersRequest(@NotNull List<@NotNull Long> employeeIds) {
}
