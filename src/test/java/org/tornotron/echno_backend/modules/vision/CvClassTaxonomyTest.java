package org.tornotron.echno_backend.modules.vision;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.tornotron.echno_backend.modules.vision.taxonomy.CvClassTaxonomy;

/**
 * The vendored file and {@link CvClassTaxonomy} agree: every class in the file is there with the
 * shape its list gives it, nothing else is, the file says which commit it came from, and the
 * image-level labels are not region classes.
 */
class CvClassTaxonomyTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CvClassTaxonomy taxonomy = new CvClassTaxonomy(mapper);

    @Test
    void theTaxonomyAndTheVendoredFileAgree() throws IOException {
        JsonNode file = file();
        List<String> objects = strings(file, "objectClasses");
        List<String> defects = strings(file, "defectClasses");

        assertThat(objects).doesNotHaveDuplicates().isNotEmpty();
        assertThat(defects).doesNotHaveDuplicates().isNotEmpty().doesNotContainAnyElementsOf(objects);
        assertThat(taxonomy.classes()).hasSize(objects.size() + defects.size());
        objects.forEach(key -> assertThat(taxonomy.shapeOf(key)).contains("BOX"));
        defects.forEach(key -> assertThat(taxonomy.shapeOf(key)).contains("POLYGON"));
        assertThat(taxonomy.negativeTypes()).containsExactlyElementsOf(strings(file, "negativeTypes"));
        assertThat(taxonomy.progressStates()).containsExactlyElementsOf(strings(file, "progressStates"));
    }

    @Test
    void theFileNamesTheCommitItCameFrom() throws IOException {
        JsonNode source = file().path("source");

        assertThat(source.path("repository").asText()).isEqualTo("tornotron/echno-datasets");
        assertThat(source.path("path").asText()).isEqualTo("echno_datasets/classes.py");
        assertThat(source.path("commit").asText()).matches("[0-9a-f]{7,40}");
    }

    @Test
    void theClassesTheFirstModelsNeedAreThere() {
        assertThat(taxonomy.shapeOf("worker")).contains("BOX");
        assertThat(taxonomy.shapeOf("ppe_helmet")).contains("BOX");
        assertThat(taxonomy.shapeOf("ppe_vest")).contains("BOX");
        assertThat(taxonomy.shapeOf("rebar")).contains("BOX");
        assertThat(taxonomy.shapeOf("crack")).contains("POLYGON");
        assertThat(taxonomy.shapeOf("honeycombing")).contains("POLYGON");
    }

    @Test
    void anImageLevelLabelIsNotARegionClass() {
        assertThat(taxonomy.negativeTypes()).contains("formwork_line");
        assertThat(taxonomy.shapeOf("formwork_line")).isEmpty();
        assertThat(taxonomy.progressStates()).contains("rework");
        assertThat(taxonomy.shapeOf("rework")).isEmpty();
        assertThat(taxonomy.shapeOf("not_a_class")).isEmpty();
    }

    private JsonNode file() throws IOException {
        try (InputStream in = new ClassPathResource(CvClassTaxonomy.RESOURCE).getInputStream()) {
            return mapper.readTree(in);
        }
    }

    private static List<String> strings(JsonNode file, String field) {
        List<String> values = new ArrayList<>();
        file.path(field).forEach(node -> values.add(node.asText()));
        return values;
    }
}
