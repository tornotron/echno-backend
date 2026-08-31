package org.tornotron.echno_backend.architecture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.tornotron.echno_backend.common.pagination.PageQuery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every paginated endpoint publishes the page size it actually serves.
 *
 * <p>{@code PageQuery} is one parameter object shared by fifty-odd endpoints, so a page size
 * written on its field is one number for all of them. Twelve of them do not serve that number:
 * they serve twenty, and the chat message listing serves thirty. The document said ten for every
 * one of them, so a client that read the contract, planned for ten rows and sent no
 * {@code pageSize} was handed twenty with nothing to tell it why. That matters here more than it
 * would elsewhere, because {@code echno-core} is written against this document rather than against
 * the running service: the contract is what a caller has. It is also how a payment listing came to
 * be summed and counted from twenty rows under a label that said all of them. Issue #662.
 *
 * <p>{@code PageSizeSchemaCustomizer} closes it by writing each operation's default from the page
 * query type that operation declares. This test is what keeps it closed, and it is deliberately
 * not written against the customizer: it takes the served default the same way a caller would
 * discover it, by constructing the declared parameter type exactly as Spring's binding does and
 * reading the size a caller who sends nothing is left with. So it fails for a document that is
 * stale, for a customizer that is removed, and for an endpoint that grows a default the document
 * was never told about, which is the thirteenth call site this whole arrangement exists to catch.
 *
 * <p>Run against {@code docs/openapi.json} as it stood before the fix it reports all twelve.
 *
 * <p>It reads the committed document rather than booting an application, for the reason
 * {@link OpenApiNullabilityTest} gives: {@code OpenApiSnapshotTest} already holds that copy to the
 * served one in a task of its own, and a second Spring context is the one thing the test JVM has
 * no room for.
 */
@AnalyzeClasses(
        packages = "org.tornotron.echno_backend",
        importOptions = ImportOption.DoNotIncludeTests.class)
class OpenApiPageSizeDefaultTest {

    /** The committed contract, relative to the project directory the test task runs in. */
    private static final Path DOCUMENT = Path.of("docs", "openapi.json");

    /** The query parameter whose default is in question. */
    private static final String PAGE_SIZE = "pageSize";

    /**
     * Reports every paginated operation whose documented page size is not the one it serves.
     *
     * @param productionClasses The imported production classes, supplied by ArchUnit.
     */
    @ArchTest
    static void everyListingDocumentsThePageSizeItServes(JavaClasses productionClasses) {
        JsonNode document = readDocument();
        List<String> wrong = new ArrayList<>();

        for (PaginatedEndpoint endpoint : paginatedEndpoints(productionClasses)) {
            JsonNode schema = pageSizeSchema(document, endpoint.path(), endpoint.httpMethod());
            if (schema == null) {
                wrong.add(endpoint + " is not in the document as an operation taking " + PAGE_SIZE);
                continue;
            }
            JsonNode documented = schema.get("default");
            if (documented == null) {
                wrong.add(endpoint + " serves " + endpoint.servedDefault()
                        + " rows and documents no default at all");
            } else if (documented.asInt() != endpoint.servedDefault()) {
                wrong.add(endpoint + " serves " + endpoint.servedDefault()
                        + " rows and documents " + documented.asInt());
            }
        }

        assertThat(wrong)
                .as("a listing that publishes one page size and serves another gives a client no "
                        + "way to know how many rows it is holding; the default belongs to the page "
                        + "query type the endpoint declares, and PageSizeSchemaCustomizer publishes "
                        + "that one rather than a second copy written by hand")
                .isEmpty();
    }

    /**
     * Every {@code pageSize} the document publishes belongs to an endpoint the rule above checked.
     *
     * <p>Without this, an endpoint the reflection cannot reach, or a path this test spells
     * differently from springdoc, would be silently unchecked rather than wrong, and the rule
     * would pass by looking at nothing.
     *
     * @param productionClasses The imported production classes, supplied by ArchUnit.
     */
    @ArchTest
    static void everyDocumentedPageSizeIsCovered(JavaClasses productionClasses) {
        Set<String> checked = new TreeSet<>();
        for (PaginatedEndpoint endpoint : paginatedEndpoints(productionClasses)) {
            checked.add(endpoint.httpMethod() + " " + endpoint.path());
        }

        Set<String> documented = new TreeSet<>();
        JsonNode paths = readDocument().get("paths");
        for (Iterator<Map.Entry<String, JsonNode>> it = paths.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> path = it.next();
            for (Iterator<Map.Entry<String, JsonNode>> ops = path.getValue().fields();
                 ops.hasNext(); ) {
                Map.Entry<String, JsonNode> operation = ops.next();
                if (parameterNamed(operation.getValue(), PAGE_SIZE) != null) {
                    documented.add(operation.getKey().toUpperCase() + " " + path.getKey());
                }
            }
        }

        assertThat(documented)
                .as("these operations publish a pageSize that the rule above never looked at, so "
                        + "nothing holds their documented default to the one they serve")
                .isSubsetOf(checked);
    }

    /**
     * Every handler that takes the page pair, with the rows it serves a caller who sends none.
     */
    private static List<PaginatedEndpoint> paginatedEndpoints(JavaClasses productionClasses) {
        List<PaginatedEndpoint> endpoints = new ArrayList<>();
        productionClasses.stream()
                .filter(javaClass -> javaClass.isMetaAnnotatedWith(Controller.class))
                .map(JavaClass::reflect)
                .forEach(controller -> {
                    for (Method method : controller.getDeclaredMethods()) {
                        RequestMapping mapping = AnnotatedElementUtils
                                .findMergedAnnotation(method, RequestMapping.class);
                        if (mapping == null) {
                            // Not a request handler. A helper that happens to take the page pair
                            // has no path and no verb, so treating it as an endpoint would invent
                            // one on the class's own mapping and on every verb there is.
                            continue;
                        }
                        Class<?> pageQueryType = pageQueryParameterOf(method);
                        if (pageQueryType == null) {
                            continue;
                        }
                        int served = servedDefault(pageQueryType);
                        for (String path : pathsOf(controller, mapping)) {
                            for (RequestMethod httpMethod : httpMethodsOf(mapping)) {
                                endpoints.add(
                                        new PaginatedEndpoint(path, httpMethod.name(), served));
                            }
                        }
                    }
                });
        return endpoints;
    }

    /** The page query type a handler declares, or {@code null} if it takes none. */
    private static Class<?> pageQueryParameterOf(Method method) {
        for (Class<?> parameterType : method.getParameterTypes()) {
            if (PageQuery.class.isAssignableFrom(parameterType)) {
                return parameterType;
            }
        }
        return null;
    }

    /**
     * The rows a caller who sends no {@code pageSize} receives, taken the way binding takes it:
     * from the declared type's own constructor, with nothing written over it.
     */
    private static int servedDefault(Class<?> pageQueryType) {
        try {
            return ((PageQuery) pageQueryType.getDeclaredConstructor().newInstance()).getPageSize();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "A page query a handler declares must be constructible: "
                            + pageQueryType.getName(), e);
        }
    }

    /** The full paths a handler answers on, class mapping and method mapping joined. */
    private static Set<String> pathsOf(Class<?> controller, RequestMapping mapping) {
        Set<String> paths = new LinkedHashSet<>();
        for (String base : declaredPaths(
                AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class))) {
            for (String tail : declaredPaths(mapping)) {
                paths.add(normalise(base + "/" + tail));
            }
        }
        return paths;
    }

    /** The paths a single {@code @RequestMapping} or one of its shorthands declares. */
    private static List<String> declaredPaths(RequestMapping mapping) {
        if (mapping == null || mapping.path().length == 0) {
            return List.of("");
        }
        return List.of(mapping.path());
    }

    /** The verbs a handler answers on, defaulting to every one when its mapping names none. */
    private static List<RequestMethod> httpMethodsOf(RequestMapping mapping) {
        if (mapping.method().length == 0) {
            return List.of(RequestMethod.values());
        }
        return List.of(mapping.method());
    }

    /**
     * Spells a joined mapping the way springdoc publishes it.
     *
     * <p>Collapses the doubled and trailing separators joining two mappings leaves, and supplies
     * the leading one: {@code CategoryController} maps {@code api/v1/workCategories} without it
     * and is published with it, since Spring treats the two spellings as the same path.
     */
    private static String normalise(String path) {
        String collapsed = path.replaceAll("/{2,}", "/");
        if (collapsed.length() > 1 && collapsed.endsWith("/")) {
            collapsed = collapsed.substring(0, collapsed.length() - 1);
        }
        return collapsed.startsWith("/") ? collapsed : "/" + collapsed;
    }

    /** The schema of an operation's {@code pageSize} parameter, or {@code null} if it has none. */
    private static JsonNode pageSizeSchema(JsonNode document, String path, String httpMethod) {
        JsonNode operations = document.path("paths").get(path);
        if (operations == null) {
            return null;
        }
        JsonNode operation = operations.get(httpMethod.toLowerCase());
        if (operation == null) {
            return null;
        }
        JsonNode parameter = parameterNamed(operation, PAGE_SIZE);
        return parameter == null ? null : parameter.get("schema");
    }

    /** An operation's query parameter of the given name, or {@code null} if it declares none. */
    private static JsonNode parameterNamed(JsonNode operation, String name) {
        for (JsonNode parameter : operation.path("parameters")) {
            if (name.equals(parameter.path("name").asText())) {
                return parameter;
            }
        }
        return null;
    }

    private static JsonNode readDocument() {
        try {
            return new ObjectMapper().readTree(Files.readString(DOCUMENT));
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "The committed OpenAPI document is what this rule reads; regenerate it with "
                            + "./gradlew openApiSnapshot -PupdateOpenApiSnapshot", e);
        }
    }

    /**
     * One operation that takes the page pair.
     *
     * @param path          The full path springdoc publishes it under.
     * @param httpMethod    The verb, upper case.
     * @param servedDefault The rows it hands a caller who sends no page size.
     */
    private record PaginatedEndpoint(String path, String httpMethod, int servedDefault) {

        @Override
        public String toString() {
            return httpMethod + " " + path;
        }
    }
}
