package org.tornotron.echno_backend.modules.inspections.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.modules.inspections.domain.OrgTrade;
import org.tornotron.echno_backend.modules.inspections.domain.TradeCatalogueEntry;
import org.tornotron.echno_backend.modules.inspections.dtos.OrgTradeDto;
import org.tornotron.echno_backend.modules.inspections.dtos.TradeCatalogueDto;

@Mapper(componentModel = "spring")
public interface TradeMapper {

    OrgTradeDto toDto(OrgTrade trade);

    TradeCatalogueDto toCatalogueDto(TradeCatalogueEntry entry);
}
