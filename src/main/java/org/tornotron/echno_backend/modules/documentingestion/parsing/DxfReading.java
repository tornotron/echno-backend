package org.tornotron.echno_backend.modules.documentingestion.parsing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * The parts of {@code dxf.json} (worker 0.3.0, contract section 7) the drawing register reads: the
 * drawing properties, the layouts and their loose text, and the block references with their attributes.
 *
 * @param truncated Whether the worker reached one of its caps, so some references may be missing
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DxfReading(String dxfVersion, Integer insUnits, List<Property> properties, List<Layout> layouts,
                         List<Insert> inserts, boolean truncated) {

    public DxfReading {
        properties = properties == null ? List.of() : List.copyOf(properties);
        layouts = layouts == null ? List.of() : List.copyOf(layouts);
        inserts = inserts == null ? List.of() : List.copyOf(inserts);
    }

    /** A custom header variable, such as a client name kept as a drawing property. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Property(String name, String value) {
    }

    /** A layout tab: model space, or a paper space sheet with its loose TEXT and MTEXT. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Layout(String name, boolean modelSpace, String paperSize, List<Text> texts) {

        public Layout {
            texts = texts == null ? List.of() : List.copyOf(texts);
        }
    }

    /**
     * A block reference that carries attributes or looks like a title block.
     *
     * @param layout          The layout it sits in
     * @param path            The enclosing blocks, outermost first, for a nested reference
     * @param titleBlockLike  Whether its name or its attribute tags say title block
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Insert(String layout, String block, List<String> path, String handle, boolean titleBlockLike,
                         List<Attribute> attributes, List<Text> texts) {

        public Insert {
            path = path == null ? List.of() : List.copyOf(path);
            attributes = attributes == null ? List.of() : List.copyOf(attributes);
            texts = texts == null ? List.of() : List.copyOf(texts);
        }
    }

    /** An ATTRIB, or a constant ATTDEF of the block. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Attribute(String tag, String value, boolean constant, boolean invisible) {
    }

    /** Plain text, with MTEXT formatting resolved; position in the layout's or block's coordinates. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Text(String text, String kind, String layer, List<Double> position) {
    }
}
