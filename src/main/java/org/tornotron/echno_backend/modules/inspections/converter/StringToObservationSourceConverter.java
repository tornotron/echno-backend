package org.tornotron.echno_backend.modules.inspections.converter;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.modules.inspections.ObservationSource;

/** Binds the slug of {@link ObservationSource} when it arrives as a query parameter on the list endpoint. */
@Component
public class StringToObservationSourceConverter implements Converter<String, ObservationSource> {

    @Override
    public ObservationSource convert(String source) {
        return ObservationSource.fromValue(source);
    }
}
