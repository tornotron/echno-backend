package org.tornotron.echno_backend.modules.__MODULE_PKG__.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.modules.__MODULE_PKG__.domain.__MODULE_PASCAL__Entry;
import org.tornotron.echno_backend.modules.__MODULE_PKG__.dto.__MODULE_PASCAL__EntryDto;

@Mapper(componentModel = "spring")
public interface __MODULE_PASCAL__Mapper {

    __MODULE_PASCAL__EntryDto toDto(__MODULE_PASCAL__Entry entry);
}
