package org.tornotron.echno_backend.inspection.converter;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.inspection.NcrStatus;

/**
 * Binds the hyphenated wire value of {@link NcrStatus} (e.g. {@code open},
 * {@code corrective-action-complete}) when it arrives as a query parameter on the
 * list endpoint. Spring's default enum binding matches the constant name, not the
 * wire value, so an explicit converter is registered.
 */
@Component
public class StringToNcrStatusConverter implements Converter<String, NcrStatus> {

    @Override
    public NcrStatus convert(String source) {
        return NcrStatus.fromValue(source);
    }
}
