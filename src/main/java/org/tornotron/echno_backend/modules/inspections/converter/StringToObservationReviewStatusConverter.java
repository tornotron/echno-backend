package org.tornotron.echno_backend.modules.inspections.converter;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.modules.inspections.ObservationReviewStatus;

/** Binds the slug of {@link ObservationReviewStatus} when it arrives as a query parameter on the list endpoint. */
@Component
public class StringToObservationReviewStatusConverter implements Converter<String, ObservationReviewStatus> {

    @Override
    public ObservationReviewStatus convert(String source) {
        return ObservationReviewStatus.fromValue(source);
    }
}
