package org.tornotron.echno_backend.modules.vision.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.tornotron.echno_backend.modules.vision.domain.CvModelFamily;
import org.tornotron.echno_backend.modules.vision.domain.CvModelTask;

@Schema(description = "A model family with its versions, oldest first.")
public record CvModelDto(
        String key,
        String name,
        CvModelTask task,
        CvModelFamily family,
        List<CvModelVersionDto> versions
) {}
