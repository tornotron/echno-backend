package org.tornotron.echno_backend.common.configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The conversion from {@code nullable = true} to the OpenAPI 3.1 form it has to take.
 *
 * <p>Each case here is a shape the DTO layer actually produces: a scalar property, a property that
 * is a reference to another model, a collection, and a model that contains itself.
 */
class NullableSchemaCustomizerTest {

    private final NullableSchemaCustomizer customizer = new NullableSchemaCustomizer();

    @Test
    void aNullableScalarGainsNullAsASecondType() {
        Schema<?> property = nullable(typed("string"));
        customise(documentWith("Dto", objectWith("name", property)));

        assertThat(property.getTypes()).containsExactly("string", "null");
    }

    @Test
    void aNonNullablePropertyIsLeftAlone() {
        Schema<?> property = typed("string");
        customise(documentWith("Dto", objectWith("name", property)));

        assertThat(property.getTypes()).containsExactly("string");
        assertThat(property.getAnyOf()).isNull();
    }

    @Test
    void aNullableReferenceBecomesAUnionWithTheNullType() {
        Schema<?> property = nullable(new Schema<>().$ref("#/components/schemas/ShiftTimingDto"));
        customise(documentWith("Dto", objectWith("shiftTiming", property)));

        assertThat(property.get$ref())
                .as("a reference cannot carry a type union, so the reference moves into the union")
                .isNull();
        assertThat(property.getAnyOf()).hasSize(2);
        assertThat(property.getAnyOf().get(0).get$ref())
                .isEqualTo("#/components/schemas/ShiftTimingDto");
        assertThat(property.getAnyOf().get(1).getTypes()).containsExactly("null");
    }

    @Test
    void aNullableCollectionIsMarkedRatherThanItsElements() {
        Schema<?> element = typed("string");
        Schema<?> property = nullable(typed("array"));
        property.setItems(element);
        customise(documentWith("Dto", objectWith("tags", property)));

        assertThat(property.getTypes()).containsExactly("array", "null");
        assertThat(element.getTypes()).containsExactly("string");
    }

    @Test
    void aSchemaThatContainsItselfIsVisitedOnce() {
        Schema<Object> tree = new Schema<>();
        tree.addType("object");
        Schema<?> children = nullable(typed("array"));
        children.setItems(tree);
        tree.addProperty("children", children);

        customise(documentWith("WbsElementDto", tree));

        assertThat(children.getTypes()).containsExactly("array", "null");
    }

    @Test
    void anInlineParameterSchemaIsReachedToo() {
        Schema<?> parameter = nullable(typed("string"));
        OpenAPI document = new OpenAPI(SpecVersion.V31).components(new Components());
        Operation operation = new Operation()
                .addParametersItem(new Parameter().name("status").schema(parameter));
        document.setPaths(new Paths());
        document.getPaths().addPathItem("/tasks", new PathItem().get(operation));

        customise(document);

        assertThat(parameter.getTypes()).containsExactly("string", "null");
    }

    @Test
    void anInlineRequestBodySchemaIsReachedToo() {
        Schema<?> property = nullable(typed("string"));
        OpenAPI document = new OpenAPI(SpecVersion.V31).components(new Components());
        Content content = new Content().addMediaType("application/json",
                new MediaType().schema(objectWith("remarks", property)));
        Operation operation = new Operation();
        operation.setRequestBody(new io.swagger.v3.oas.models.parameters.RequestBody()
                .content(content));
        document.setPaths(new Paths());
        document.getPaths().addPathItem("/tasks", new PathItem().post(operation));

        customise(document);

        assertThat(property.getTypes()).containsExactly("string", "null");
    }

    @Test
    void aThreePointZeroDocumentKeepsTheKeywordItCanStillWrite() {
        Schema<?> property = nullable(typed("string"));
        OpenAPI document = new OpenAPI(SpecVersion.V30).components(new Components());
        document.getComponents().addSchemas("Dto", objectWith("name", property));

        customise(document);

        assertThat(property.getNullable())
                .as("3.0 serializes the keyword as it stands, so rewriting it would lose it")
                .isTrue();
        assertThat(property.getTypes()).containsExactly("string");
    }

    private void customise(OpenAPI document) {
        customizer.customise(document);
    }

    private static OpenAPI documentWith(String name, Schema<?> schema) {
        OpenAPI document = new OpenAPI(SpecVersion.V31).components(new Components());
        document.getComponents().addSchemas(name, schema);
        return document;
    }

    private static Schema<Object> objectWith(String property, Schema<?> schema) {
        Schema<Object> object = new Schema<>();
        object.addType("object");
        object.addProperty(property, schema);
        return object;
    }

    private static Schema<Object> typed(String type) {
        Schema<Object> schema = new Schema<>();
        schema.addType(type);
        return schema;
    }

    private static Schema<Object> nullable(Schema<Object> schema) {
        schema.setNullable(true);
        return schema;
    }
}
