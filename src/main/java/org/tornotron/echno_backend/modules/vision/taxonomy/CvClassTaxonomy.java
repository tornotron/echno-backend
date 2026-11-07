package org.tornotron.echno_backend.modules.vision.taxonomy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.modules.vision.VisionModuleEnabled;

/**
 * The classes a model may output and a reviewer may choose, read from
 * {@code vision/classes.json}, which is vendored from echno-datasets ({@code classes.py}) with the
 * source commit in its header. The dataset repository is the record; this copy changes only by
 * replacing the file.
 *
 * <p>The rule the class keys carry: object and PPE classes are drawn as boxes, defect classes as
 * polygons. Negative types and progress states are image-level labels, not region classes, so a
 * region cannot name one.
 */
@Component
@VisionModuleEnabled
public class CvClassTaxonomy {

    public static final String RESOURCE = "vision/classes.json";
    public static final String BOX = "BOX";
    public static final String POLYGON = "POLYGON";

    private final Map<String, String> shapes;
    private final List<String> negativeTypes;
    private final List<String> progressStates;

    public CvClassTaxonomy(ObjectMapper objectMapper) {
        JsonNode file = read(objectMapper);
        Map<String, String> byKey = new LinkedHashMap<>();
        strings(file, "objectClasses").forEach(key -> byKey.put(key, BOX));
        strings(file, "defectClasses").forEach(key -> byKey.put(key, POLYGON));
        this.shapes = Collections.unmodifiableMap(byKey);
        this.negativeTypes = strings(file, "negativeTypes");
        this.progressStates = strings(file, "progressStates");
    }

    /** The shape a class is drawn as, or empty when the key is not a region class. */
    public Optional<String> shapeOf(String classKey) {
        return Optional.ofNullable(shapes.get(classKey));
    }

    /** Every region class, objects first then defects, as the source lists them. */
    public Map<String, String> classes() {
        return shapes;
    }

    public List<String> negativeTypes() {
        return negativeTypes;
    }

    public List<String> progressStates() {
        return progressStates;
    }

    private static JsonNode read(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return objectMapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + RESOURCE, e);
        }
    }

    private static List<String> strings(JsonNode file, String field) {
        List<String> values = new ArrayList<>();
        file.path(field).forEach(node -> values.add(node.asText()));
        return List.copyOf(values);
    }
}
