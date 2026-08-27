package org.tornotron.echno_backend.inspection.converter;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.inspection.NcrType;

/**
 * Binds the lowercase wire value of {@link NcrType} ({@code quality},
 * {@code safety}) when it arrives as a query parameter on the list endpoint.
 * Spring's default enum binding matches the constant name, not the wire value, so
 * an explicit converter is registered.
 */
@Component
public class StringToNcrTypeConverter implements Converter<String, NcrType> {

    @Override
    public NcrType convert(String source) {
        return NcrType.fromValue(source);
    }
}
