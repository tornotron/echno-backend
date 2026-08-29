package org.tornotron.echno_backend.openapi;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Writes the OpenAPI document to a file in the repository, and fails when the committed copy no
 * longer matches what the code produces.
 *
 * <p>Until this existed the document had no artifact at all: springdoc built it, but only in the
 * memory of a booted application, on {@code GET /v3/api-docs}. Anyone wanting a copy had to run a
 * server and curl it. That is the blocker tornotron/echno-core#49 ran into. A client cannot be
 * checked against a document that is not published, and pinning a hand-curled copy as a fixture is
 * worse than no check, because nothing then notices when the fixture stops describing the backend
 * and the suite goes on reporting green.
 *
 * <p>So the document is committed, at {@code docs/openapi.json}, and this test is what keeps it
 * honest. Two things follow from committing it rather than only publishing it from CI:
 *
 * <ul>
 *   <li>A change to a DTO field name arrives in review as a diff on the contract, next to the code
 *       that caused it. On a hand-maintained client with no code generation anywhere, that diff is
 *       the only place a rename is visible before it reaches a user.
 *   <li>Any consumer can read the contract at a git ref without a running server, which is what
 *       echno-core's contract test needs.
 * </ul>
 *
 * <h2>Regenerating</h2>
 *
 * <pre>
 *   ./gradlew openApiSnapshot                             # verify the committed copy
 *   ./gradlew openApiSnapshot -PupdateOpenApiSnapshot     # rewrite it
 * </pre>
 *
 * <p>The task is deliberately not part of {@code test} or {@code check}. It boots a second
 * application context, and the main suite runs in a 1 GB JVM with a bounded context cache
 * (see the {@code test} block in build.gradle) where an extra context is not free. Its own Gradle
 * task means its own forked JVM and its own heap, so the cost lands nowhere near the suite. CI runs
 * it as a separate step.
 *
 * <h2>The property that has to be on</h2>
 *
 * <p>{@code springdoc.swagger-ui.public-access} ships as false (issue #569), and with it off the
 * docs paths answer 401 to an unauthenticated caller. springdoc still builds the document; Spring
 * Security just refuses to hand it over. The property is set true below for this context only, so
 * the dump reads the document without authenticating and without weakening any deployed default.
 *
 * <h2>Canonical form</h2>
 *
 * <p>The response is re-serialised with its object keys sorted before it is written. springdoc
 * assembles paths and schemas from hash-ordered maps, so two runs of the same code can emit the
 * same document with its members in a different order. Committing that raw would produce a diff on
 * every regeneration and hide the real ones. Sorting makes the file a function of the code alone,
 * which is what makes a diff on it worth reading.
 */
@SpringBootTest(properties = {
        "springdoc.swagger-ui.public-access=true",
        "BACKEND_API_VERSION=v1",
        "DIGITAL_OCEAN_SPACES_URI=http://localhost:9000",
        "DIGITAL_OCEAN_SPACES_KEY_ID=test",
        "DIGITAL_OCEAN_SPACES_KEY_SECRET=test",
        "DIGITAL_OCEAN_SPACES_BUCKET_NAME=test-bucket",
        "DIGITAL_OCEAN_SPACES_CDN_ENDPOINT=http://localhost:9000",
        // A throwaway 32-character value, the length the encryption service asks for, so the
        // context can build. Nothing is encrypted here.
        "ENCRYPTION_SECRET_KEY=0123456789abcdef0123456789abcdef", // gitleaks:allow
        "KEYCLOAK_BACKEND_ADMIN_EMAIL=admin@test.local",
        "KEYCLOAK_BACKEND_ADMIN_FIRST_NAME=Test",
        "KEYCLOAK_BACKEND_ADMIN_LAST_NAME=Admin",
        "KEYCLOAK_BACKEND_ADMIN_PASSWORD=pw",
        "KEYCLOAK_BACKEND_ADMIN_USERNAME=admin",
        "KEYCLOAK_BACKEND_CLIENT=backend",
        "KEYCLOAK_BACKEND_REDIRECT_URI=http://localhost/*",
        "KEYCLOAK_BACKEND_REDIRECT_URI_APIDOG=http://localhost/webjars/*",
        "KEYCLOAK_BACKEND_SECRET=secret",
        "KEYCLOAK_BACKEND_SERVICE_EMAIL=svc@test.local",
        "KEYCLOAK_BACKEND_SERVICE_FIRST_NAME=Test",
        "KEYCLOAK_BACKEND_SERVICE_LAST_NAME=Service",
        "KEYCLOAK_BACKEND_SERVICE_PASSWORD=pw",
        "KEYCLOAK_BACKEND_SERVICE_USERNAME=svc",
        "KEYCLOAK_BACKEND_WEB_ORIGIN=http://localhost",
        "KEYCLOAK_FRONTEND_CLIENT=frontend",
        "KEYCLOAK_FRONTEND_REDIRECT_URI=http://localhost/*",
        "KEYCLOAK_FRONTEND_WEB_ORIGIN=http://localhost",
        "KEYCLOAK_INITIALIZER_APPLICATION_REALM=echno-realm",
        "KEYCLOAK_INITIALIZER_CLIENT_ID=admin-cli",
        "KEYCLOAK_INITIALIZER_MASTER_REALM=master",
        "KEYCLOAK_INITIALIZER_PASSWORD=pw",
        "KEYCLOAK_INITIALIZER_URL=http://localhost:0",
        "KEYCLOAK_INITIALIZER_USERNAME=admin",
        "SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KEYCLOAK_ISSUER_URI=http://localhost:0/realms/echno-realm"
})
@AutoConfigureMockMvc
class OpenApiSnapshotTest extends AbstractIntegrationTest {

    /** Where the committed document lives, passed in by the Gradle task that runs this. */
    private static final String SNAPSHOT_PATH_PROPERTY = "echno.openapi.snapshot";

    /** Set by {@code -PupdateOpenApiSnapshot} to rewrite the file instead of checking it. */
    private static final String UPDATE_PROPERTY = "echno.openapi.update";

    /** Where the freshly served document is written, so a failure can be diffed by hand. */
    private static final String ACTUAL_PATH_PROPERTY = "echno.openapi.actual";

    /** How many differing lines a failure prints before it stops and points at the two files. */
    private static final int DIFFERENCE_LINE_LIMIT = 40;

    /**
     * Arrays springdoc fills from a set rather than a sequence, so their order carries no meaning
     * and is not stable between runs. Sorted along with the object keys. Every other array (a
     * parameter list, an enum's constants) follows declaration order in the source and is left
     * alone, because a reordering there is a real change to the code.
     */
    private static final List<String> SET_VALUED_ARRAYS = List.of("required", "tags");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theCommittedDocumentMatchesWhatTheCodeProduces() throws Exception {
        String served = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String canonical = canonicalise(MAPPER.readTree(served));
        Path snapshot = Path.of(System.getProperty(SNAPSHOT_PATH_PROPERTY,
                "docs/openapi.json"));

        if (Boolean.getBoolean(UPDATE_PROPERTY)) {
            Files.createDirectories(snapshot.getParent());
            Files.writeString(snapshot, canonical, StandardCharsets.UTF_8);
            return;
        }

        // Written whether or not the comparison passes, so a failure can be diffed with any tool
        // rather than read out of a test report.
        Path actual = Path.of(System.getProperty(ACTUAL_PATH_PROPERTY,
                "build/openapi.json"));
        Files.createDirectories(actual.getParent());
        Files.writeString(actual, canonical, StandardCharsets.UTF_8);

        assertThat(snapshot)
                .as("the OpenAPI document has no committed copy at %s; run "
                        + "./gradlew openApiSnapshot -PupdateOpenApiSnapshot", snapshot)
                .exists();

        String committed = Files.readString(snapshot, StandardCharsets.UTF_8);
        if (committed.equals(canonical)) {
            return;
        }

        throw new AssertionError("""
                The committed OpenAPI document no longer matches what this code serves.

                  committed: %s
                  served:    %s

                If the API change is intended, regenerate the document and commit it with the \
                code change, so the contract change is reviewed alongside its cause:

                  ./gradlew openApiSnapshot -PupdateOpenApiSnapshot

                Clients read this file. tornotron/echno-core checks the field names it sends \
                against these schemas, and nothing generates code from it on either side, so a \
                rename here is the only warning a client gets.

                %s"""
                .formatted(snapshot, actual, summariseDifference(committed, canonical)));
    }

    /**
     * A readable account of how two 3 MB documents differ.
     *
     * <p>Handing the whole document to an equality assertion produces a failure nobody reads: two
     * megabytes of identical JSON with the changed line somewhere inside it. Both sides are
     * canonical, one key per line, so comparing the lines as multisets isolates exactly what
     * changed and reads the way the intended change reads. A renamed field shows as one removed
     * line and one added line naming the old and new field.
     */
    private static String summariseDifference(String committed, String served) {
        List<String> removed = linesOnlyIn(committed, served);
        List<String> added = linesOnlyIn(served, committed);

        StringBuilder summary = new StringBuilder();
        appendLines(summary, "Only in the committed document (removed or renamed)", removed);
        appendLines(summary, "Only in what this code serves (added or renamed)", added);
        return summary.toString();
    }

    private static List<String> linesOnlyIn(String source, String other) {
        Map<String, Integer> otherCounts = new HashMap<>();
        for (String line : other.split("\n")) {
            otherCounts.merge(line.strip(), 1, Integer::sum);
        }
        List<String> only = new ArrayList<>();
        for (String line : source.split("\n")) {
            String stripped = line.strip();
            Integer remaining = otherCounts.get(stripped);
            if (remaining == null || remaining == 0) {
                only.add(stripped);
            } else {
                otherCounts.put(stripped, remaining - 1);
            }
        }
        return only;
    }

    private static void appendLines(StringBuilder summary, String heading, List<String> lines) {
        summary.append(heading).append(" (").append(lines.size()).append("):\n");
        if (lines.isEmpty()) {
            summary.append("  (none)\n");
            return;
        }
        lines.stream().limit(DIFFERENCE_LINE_LIMIT).forEach(line ->
                summary.append("  ").append(line).append('\n'));
        if (lines.size() > DIFFERENCE_LINE_LIMIT) {
            summary.append("  ... and ")
                    .append(lines.size() - DIFFERENCE_LINE_LIMIT)
                    .append(" more; diff the two files above for the rest\n");
        }
    }

    /**
     * Sorts every object's keys, and the set-valued arrays, then pretty-prints with two spaces and
     * LF line endings so the file reads as a document rather than as one long line.
     */
    private static String canonicalise(JsonNode document) throws IOException {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withSeparators(Separators.createDefaultInstance()
                        .withObjectFieldValueSpacing(Separators.Spacing.AFTER))
                .withObjectIndenter(new DefaultIndenter("  ", "\n"))
                .withArrayIndenter(new DefaultIndenter("  ", "\n"));
        ObjectWriter writer = MAPPER.writer(printer);
        return writer.writeValueAsString(sortNode(document, null)) + "\n";
    }

    private static JsonNode sortNode(JsonNode node, String fieldName) {
        if (node.isObject()) {
            ObjectNode sorted = MAPPER.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            for (String name : names) {
                sorted.set(name, sortNode(node.get(name), name));
            }
            return sorted;
        }
        if (node.isArray()) {
            List<JsonNode> elements = new ArrayList<>();
            node.forEach(element -> elements.add(sortNode(element, null)));
            if (SET_VALUED_ARRAYS.contains(fieldName)) {
                elements.sort(Comparator.comparing(OpenApiSnapshotTest::sortKeyOf));
            }
            ArrayNode sorted = MAPPER.createArrayNode();
            elements.forEach(sorted::add);
            return sorted;
        }
        return node.deepCopy();
    }

    /**
     * Orders a set-valued array's elements. {@code required} holds plain strings; the root
     * {@code tags} array holds objects with a name, which is what identifies them.
     */
    private static String sortKeyOf(JsonNode element) {
        if (element.isObject() && element.has("name")) {
            return element.get("name").asText();
        }
        return element.isTextual() ? element.asText() : element.toString();
    }
}
