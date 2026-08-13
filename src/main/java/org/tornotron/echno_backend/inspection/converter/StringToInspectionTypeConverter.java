package org.tornotron.echno_backend.inspection.converter;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.inspection.InspectionType;

/**
 * Binds the hyphenated wire value of {@link InspectionType} (e.g. {@code final},
 * {@code safety}) when it arrives as a query parameter on the list endpoint.
 * Spring's default enum binding matches the constant name, not the wire value,
 * so an explicit converter is registered.
 */
@Component
public class StringToInspectionTypeConverter implements Converter<String, InspectionType> {

    @Override
    public InspectionType convert(String source) {
        return InspectionType.fromValue(source);
    }
}
