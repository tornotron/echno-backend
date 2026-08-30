package org.tornotron.echno_backend.common.history.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.common.history.StatusTransition;
import org.tornotron.echno_backend.common.history.dto.StatusTransitionDto;

/**
 * Maps {@link StatusTransition} to its DTO.
 *
 * <p>{@code entityType} and {@code entityId} are left off deliberately. A trail is always read
 * through the endpoint of the record it belongs to, so both are already known to the caller, and
 * echoing the polymorphic key in the response would invite a client to treat it as an address it
 * can ask about directly.
 */
@Mapper(componentModel = "spring")
public interface StatusTransitionMapper {

    StatusTransitionDto toDto(StatusTransition transition);
}
