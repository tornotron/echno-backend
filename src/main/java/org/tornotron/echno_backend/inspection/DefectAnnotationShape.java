package org.tornotron.echno_backend.inspection;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The mark drawn over a defect photo. Every shape is described by the same two
 * points, so one geometry validates and renders for all three:
 *
 * <ul>
 *   <li>{@link #RECTANGLE} and {@link #ELLIPSE} treat the points as opposite
 *       corners of the bounding box, in either order.</li>
 *   <li>{@link #ARROW} treats the first point as the tail and the second as the
 *       head, so the order carries meaning.</li>
 * </ul>
 *
 * <p>Deliberately three shapes and no freehand path. A freehand stroke needs a
 * variable-length point list, which is a different storage shape and a different
 * validation problem; these three cover marking up a defect photo and can be
 * drawn identically by a PDF renderer and a browser.
 */
public enum DefectAnnotationShape {
    RECTANGLE("rectangle"),
    ELLIPSE("ellipse"),
    ARROW("arrow");

    private final String value;

    DefectAnnotationShape(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static DefectAnnotationShape fromValue(String value) {
        for (DefectAnnotationShape shape : values()) {
            if (shape.value.equalsIgnoreCase(value)) {
                return shape;
            }
        }
        throw new IllegalArgumentException("Unknown defect annotation shape: " + value);
    }
}
