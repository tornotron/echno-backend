package org.tornotron.echno_backend.inspection.converter;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.inspection.InspectionTrade;

/**
 * Binds the hyphenated wire value of {@link InspectionTrade} (e.g. {@code rcc},
 * {@code shuttering-formwork}) when it arrives as a query parameter on the list
 * endpoint. Spring's default enum binding matches the constant name, not the wire
 * value, so an explicit converter is registered.
 */
@Component
public class StringToInspectionTradeConverter implements Converter<String, InspectionTrade> {

    @Override
    public InspectionTrade convert(String source) {
        return InspectionTrade.fromValue(source);
    }
}
