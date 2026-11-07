package org.tornotron.echno_backend.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The committed document's operation ids are unique and carry no numbering (#878). A numeric
 * suffix is springdoc's order-dependent default showing through, which is what made adding one
 * controller rewrite the ids of unrelated ones.
 */
class OpenApiOperationIdsTest {

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    @Test
    void everyOperationIdIsUniqueAndUnnumbered() throws Exception {
        JsonNode paths = new ObjectMapper().readTree(Path.of("docs/openapi.json").toFile()).get("paths");
        List<String> ids = new ArrayList<>();
        paths.forEach(path -> path.properties().forEach(entry -> {
            if (HTTP_METHODS.contains(entry.getKey())) {
                ids.add(entry.getValue().get("operationId").asText());
            }
        }));

        assertThat(ids).isNotEmpty().doesNotHaveDuplicates();
        assertThat(ids).noneMatch(id -> id.matches(".*_[0-9]+"));
    }
}
