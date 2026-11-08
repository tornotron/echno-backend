package org.tornotron.echno_backend.modules.vision.jobs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * The worker's {@code detections.json}, as the job contract lays it out. Coordinates are fractions
 * of the image as displayed, after EXIF orientation. Unknown fields are ignored, so the worker can
 * add to the file without a backend release.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DetectionsFile(
        String jobId,
        String modelKey,
        String modelVersion,
        Image image,
        List<Detection> detections,
        Integer inferenceMs,
        String workerVersion,
        String runtime
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Image(Integer width, Integer height, String sha256, Integer exifOrientationApplied) {}

    /**
     * One detection.
     *
     * @param shape  {@code box} or {@code polygon}
     * @param box    {@code [x1, y1, x2, y2]} for a box
     * @param points {@code [[x, y], ...]} for a polygon
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Detection(String classKey, Double score, String shape, List<Double> box, List<List<Double>> points) {}
}
