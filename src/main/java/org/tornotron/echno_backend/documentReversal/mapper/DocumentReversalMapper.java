package org.tornotron.echno_backend.documentReversal.mapper;

import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.documentReversal.DocumentReversal;
import org.tornotron.echno_backend.documentReversal.dto.DocumentReversalDto;
import org.tornotron.echno_backend.user.UserNameLookup;

/**
 * Maps a reversal to its DTO. The requester and decider names come from the
 * {@link UserNameLookup} the caller hands in, the shape {@code StockAdjustmentMapper} uses, so
 * the mapper touches no repository.
 */
@Mapper(componentModel = "spring")
public interface DocumentReversalMapper {

    @Mapping(source = "organization.id", target = "organizationId")
    @Mapping(target = "requestedByName", expression = "java(names.nameOf(reversal.getRequestedBy()))")
    @Mapping(target = "decidedByName", expression = "java(names.nameOf(reversal.getDecidedBy()))")
    DocumentReversalDto toDto(DocumentReversal reversal, @Context UserNameLookup names);
}
