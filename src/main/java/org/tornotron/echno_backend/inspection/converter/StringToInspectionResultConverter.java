package org.tornotron.echno_backend.inspection.converter;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.inspection.InspectionResult;

/**
 * Binds the hyphenated wire value of {@link InspectionResult} (e.g.
 * {@code passed-with-remarks}) when it arrives as a query parameter on the
 * list endpoint.
 */
@Component
public class StringToInspectionResultConverter implements Converter<String, InspectionResult> {

    @Override
    public InspectionResult convert(String source) {
        return InspectionResult.fromValue(source);
    }
}
