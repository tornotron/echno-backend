package org.tornotron.echno_backend.inspection.converter;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.inspection.InspectionCategory;

/**
 * Binds the hyphenated wire value of {@link InspectionCategory} (e.g. {@code qa-qc},
 * {@code safety}) when it arrives as a query parameter on the list endpoint.
 * Spring's default enum binding matches the constant name, not the wire value,
 * so an explicit converter is registered.
 */
@Component
public class StringToInspectionCategoryConverter implements Converter<String, InspectionCategory> {

    @Override
    public InspectionCategory convert(String source) {
        return InspectionCategory.fromValue(source);
    }
}
