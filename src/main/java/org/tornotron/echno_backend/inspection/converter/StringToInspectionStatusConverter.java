package org.tornotron.echno_backend.inspection.converter;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.inspection.InspectionStatus;

/**
 * Binds the hyphenated wire value of {@link InspectionStatus} (e.g.
 * {@code in-progress}) when it arrives as a query parameter on the list endpoint.
 */
@Component
public class StringToInspectionStatusConverter implements Converter<String, InspectionStatus> {

    @Override
    public InspectionStatus convert(String source) {
        return InspectionStatus.fromValue(source);
    }
}
