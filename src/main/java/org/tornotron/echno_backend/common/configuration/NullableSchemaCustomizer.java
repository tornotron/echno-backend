package org.tornotron.echno_backend.common.configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders {@code @Schema(nullable = true)} into the document, which OpenAPI 3.1 otherwise drops
 * on the floor.
 *
 * <h2>What was wrong</h2>
 *
 * <p>The document this application serves is OpenAPI 3.1: springdoc 2.8 defaults
 * {@code springdoc.api-docs.version} to {@code openapi_3_1}, and nothing here overrides it. In
 * 3.0 a field that may be null is written {@code "type": "string", "nullable": true}; 3.1 dropped
 * that keyword in favour of a type union, {@code "type": ["string", "null"]}.
 *
 * <p>swagger-core 2.2.29 reads the annotation either way: {@code @Schema(nullable = true)} lands
 * on the model as {@code Schema.nullable}. What it does not do is translate it. Its 3.1
 * serializer mixin marks {@code getNullable()} as ignored and writes {@code types} instead, so
 * under 3.1 the annotation resolves, is held in memory, and is then silently discarded on the way
 * out. Setting it produced no diff on the document at all, which is why nobody had set it: the
 * one annotation an author would reach for looked like it did nothing.
 *
 * <p>The result was a 3 MB contract in which not one of 3069 properties said anything about being
 * null, while a large share of them are null in practice. A consumer reading it is told every
 * field is always present, and the {@code ?? 0} written on the strength of that turns a value
 * nobody measured into a confident wrong one. See issue #645.
 *
 * <h2>What this does</h2>
 *
 * <p>Runs after the document is assembled and before it is serialized, and converts every schema
 * carrying {@code nullable = true} into the 3.1 form:
 *
 * <ul>
 *   <li>a schema with a type gains {@code "null"} as a second member of it, giving
 *       {@code "type": ["string", "null"]};
 *   <li>a schema that is a bare {@code $ref} to another model becomes
 *       {@code "anyOf": [{"$ref": ...}, {"type": "null"}]}, because a union needs a type to add
 *       to and a reference has none.
 * </ul>
 *
 * <p>Anything else carrying the flag is logged rather than changed, since guessing at a form for
 * it would put a claim in the contract that nothing verified. A document served as 3.0, which
 * {@code springdoc.api-docs.version} can still ask for, is left alone: the keyword is written out
 * as it stands there, and rewriting it into a union would lose it.
 *
 * <h2>Why a customizer and not a model converter</h2>
 *
 * <p>A {@code ModelConverter} sees each type as it is resolved and would have to reproduce
 * swagger-core's rules for when a property is inlined and when it becomes a reference. The flag
 * survives on the assembled document either way, so reading it there needs none of that: one walk
 * over the finished object, keyed on a field that is about to be thrown away regardless.
 */
@Component
@Slf4j
public class NullableSchemaCustomizer implements OpenApiCustomizer {

    /** The JSON Schema type that admits only null. */
    private static final String NULL_TYPE = "null";

    @Override
    public void customise(OpenAPI openApi) {
        if (!SpecVersion.V31.equals(openApi.getSpecVersion())) {
            // Only 3.1 needs the translation. Serve the document as 3.0 and the keyword is
            // written out as it stands, so rewriting it into a union there would lose it.
            return;
        }
        Set<Schema<?>> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Schema<?> root : rootsOf(openApi)) {
            visit(root, visited);
        }
    }

    /**
     * Every schema the document reaches from somewhere other than another schema: the named
     * models, and the schemas written inline on parameters, request bodies and responses.
     */
    private static List<Schema<?>> rootsOf(OpenAPI openApi) {
        List<Schema<?>> roots = new ArrayList<>();
        Components components = openApi.getComponents();
        if (components != null) {
            addAll(roots, components.getSchemas());
            if (components.getParameters() != null) {
                components.getParameters().values().forEach(p -> add(roots, p.getSchema()));
            }
            if (components.getRequestBodies() != null) {
                components.getRequestBodies().values()
                        .forEach(body -> addContent(roots, body.getContent()));
            }
            if (components.getResponses() != null) {
                components.getResponses().values()
                        .forEach(response -> addContent(roots, response.getContent()));
            }
            if (components.getHeaders() != null) {
                components.getHeaders().values().forEach(h -> add(roots, h.getSchema()));
            }
        }
        if (openApi.getPaths() != null) {
            for (PathItem path : openApi.getPaths().values()) {
                addParameters(roots, path.getParameters());
                path.readOperations().forEach(operation -> addOperation(roots, operation));
            }
        }
        return roots;
    }

    private static void addOperation(List<Schema<?>> roots, Operation operation) {
        addParameters(roots, operation.getParameters());
        RequestBody body = operation.getRequestBody();
        if (body != null) {
            addContent(roots, body.getContent());
        }
        if (operation.getResponses() != null) {
            for (ApiResponse response : operation.getResponses().values()) {
                addContent(roots, response.getContent());
                if (response.getHeaders() != null) {
                    response.getHeaders().values().forEach(h -> add(roots, h.getSchema()));
                }
            }
        }
    }

    private static void addParameters(List<Schema<?>> roots, List<Parameter> parameters) {
        if (parameters != null) {
            parameters.forEach(parameter -> add(roots, parameter.getSchema()));
        }
    }

    private static void addContent(List<Schema<?>> roots, Content content) {
        if (content != null) {
            content.values().stream().map(MediaType::getSchema)
                    .forEach(schema -> add(roots, schema));
        }
    }

    private static void addAll(List<Schema<?>> roots, Map<String, Schema> schemas) {
        if (schemas != null) {
            schemas.values().forEach(schema -> add(roots, schema));
        }
    }

    private static void add(List<Schema<?>> roots, Schema<?> schema) {
        if (schema != null) {
            roots.add(schema);
        }
    }

    /**
     * Applies the conversion to one schema and everything nested inside it. Identity-keyed
     * because a schema instance is shared between the places that reference it, and because a
     * self-referencing model (a WBS element with child elements) would otherwise not terminate.
     */
    private void visit(Schema<?> schema, Set<Schema<?>> visited) {
        if (schema == null || !visited.add(schema)) {
            return;
        }
        admitNull(schema);

        if (schema.getProperties() != null) {
            schema.getProperties().values().forEach(property -> visit(property, visited));
        }
        visit(schema.getItems(), visited);
        if (schema.getAdditionalProperties() instanceof Schema<?> additional) {
            visit(additional, visited);
        }
        visitAll(schema.getAllOf(), visited);
        visitAll(schema.getAnyOf(), visited);
        visitAll(schema.getOneOf(), visited);
        visit(schema.getNot(), visited);
    }

    private void visitAll(Collection<Schema> schemas, Set<Schema<?>> visited) {
        if (schemas != null) {
            schemas.forEach(schema -> visit(schema, visited));
        }
    }

    /**
     * Rewrites one schema so that the document says it admits null, and clears the flag once it
     * has, so that a second build over the same model is a no-op rather than a second rewrite.
     */
    private void admitNull(Schema<?> schema) {
        if (!Boolean.TRUE.equals(schema.getNullable())) {
            return;
        }
        if (schema.getTypes() != null && !schema.getTypes().isEmpty()) {
            schema.addType(NULL_TYPE);
            schema.setNullable(null);
            return;
        }
        if (schema.get$ref() != null) {
            Schema<?> nullType = new Schema<>();
            nullType.addType(NULL_TYPE);
            List<Schema> union = new ArrayList<>();
            union.add(new Schema<>().$ref(schema.get$ref()));
            union.add(nullType);
            schema.set$ref(null);
            schema.setAnyOf(union);
            schema.setNullable(null);
            return;
        }
        log.warn("A schema is marked nullable but has neither a type nor a $ref to build a union "
                + "from, so the document cannot say it admits null: {}", schema.getName());
    }
}
