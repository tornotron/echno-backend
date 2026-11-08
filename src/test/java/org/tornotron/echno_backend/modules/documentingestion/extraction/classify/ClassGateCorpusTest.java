package org.tornotron.echno_backend.modules.documentingestion.extraction.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocClass;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocumentFileType;
import org.tornotron.echno_backend.modules.documentingestion.parsing.PageText;
import org.tornotron.echno_backend.modules.documentingestion.parsing.ParsedOutputReader;

/**
 * The class gate of design section 5 (90 percent correct per document, on text PDF and Excel), for the
 * deterministic classifier alone, scored against the private evaluation corpus. Runs only when
 * {@code ECHNO_INGEST_CORPUS} names a directory holding the corpus {@code manifest.jsonl} and the
 * worker's outputs under {@code parsed/<document_id>/}; CI has neither, so it is skipped there.
 *
 * <p>A document the deterministic reading leaves undecided goes to the LLM in production, so it counts
 * against coverage and not against precision. The gate asserted here is precision on text documents:
 * when the deterministic reading names a class, it must be right. The report prints numbers only,
 * never a file name or any text, since the corpus is private.
 */
@EnabledIfEnvironmentVariable(named = "ECHNO_INGEST_CORPUS", matches = ".+")
class ClassGateCorpusTest {

    static final double GATE = 0.90;

    final ObjectMapper json = new ObjectMapper();

    record Score(int documents, int decided, int correct) {

        Score plus(boolean decided, boolean correct) {
            return new Score(documents + 1, this.decided + (decided ? 1 : 0), this.correct + (correct ? 1 : 0));
        }

        double precision() {
            return decided == 0 ? 0 : (double) correct / decided;
        }

        double accuracy() {
            return documents == 0 ? 0 : (double) correct / documents;
        }
    }

    @Test
    void theDeterministicClassifierMeetsTheClassGateOnTextDocuments() throws IOException {
        Path corpus = Path.of(System.getenv("ECHNO_INGEST_CORPUS"));
        Score text = new Score(0, 0, 0);
        Score scanned = new Score(0, 0, 0);
        int labelledPages = 0;
        int pagesRight = 0;
        for (String line : Files.readAllLines(corpus.resolve("manifest.jsonl"), StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode doc = json.readTree(line);
            Path parsed = corpus.resolve("parsed").resolve(doc.path("document_id").asText());
            if (doc.path("use_restricted").asBoolean(false) || !Files.isDirectory(parsed)) {
                continue;
            }
            DocumentFileType type = DocumentFileType.valueOf(doc.path("file_type").asText().toUpperCase(Locale.ROOT));
            List<PageText> pages = Files.exists(parsed.resolve("pages.jsonl"))
                    ? ParsedOutputReader.parsePages(Files.readAllBytes(parsed.resolve("pages.jsonl")), json) : List.of();
            List<String> sheets = new ArrayList<>();
            if (Files.exists(parsed.resolve("meta.json"))) {
                json.readTree(parsed.resolve("meta.json").toFile()).path("sheets").forEach(s -> sheets.add(s.path("name").asText()));
            }
            Optional<DeterministicClassifier.Decision> decision = DeterministicClassifier.classify(fileName(doc), type,
                    sheets, pages);
            DocClass expected = DocClass.parse(doc.path("document_class").asText()).orElse(DocClass.OTHER);
            boolean correct = decision.map(d -> d.documentClass() == expected).orElse(false);
            if ("scanned".equals(doc.path("surface").asText())) {
                scanned = scanned.plus(decision.isPresent(), correct);
            } else {
                text = text.plus(decision.isPresent(), correct);
            }
            for (JsonNode segment : doc.path("segments")) {
                if (!segment.has("pages") || decision.isEmpty()) {
                    continue;
                }
                String[] range = segment.path("pages").asText().split("-");
                DocClass labelled = DocClass.parse(segment.path("document_class").asText()).orElse(DocClass.OTHER);
                for (int p = Integer.parseInt(range[0]); p <= Integer.parseInt(range[range.length - 1]); p++) {
                    labelledPages++;
                    if (classOf(decision.get(), p) == labelled) {
                        pagesRight++;
                    }
                }
            }
        }
        System.out.printf(Locale.ROOT, "Class gate, text: %d documents, %d decided, %d correct; precision %.3f, "
                        + "accuracy %.3f%n", text.documents(), text.decided(), text.correct(), text.precision(), text.accuracy());
        System.out.printf(Locale.ROOT, "Class gate, scanned (reported, no gate): %d documents, %d decided, %d correct%n",
                scanned.documents(), scanned.decided(), scanned.correct());
        System.out.printf(Locale.ROOT, "Page splits on labelled ranges: %d of %d pages in the labelled class%n", pagesRight,
                labelledPages);

        assertThat(text.documents()).as("the corpus has text documents").isPositive();
        assertThat(text.precision()).as("precision on text documents the deterministic reading decides")
                .isGreaterThanOrEqualTo(GATE);
    }

    private static DocClass classOf(DeterministicClassifier.Decision d, int page) {
        for (DeterministicClassifier.ProposedSegment s : d.segments()) {
            if (s.pageFrom() != null && page >= s.pageFrom() && page <= s.pageTo()) {
                return s.docClass();
            }
        }
        return d.documentClass();
    }

    /** The name the file had where it was published, as a person uploading it would keep it. */
    private static String fileName(JsonNode doc) {
        String url = doc.path("source_url").asText();
        String last = url.substring(url.lastIndexOf('/') + 1);
        int query = last.indexOf('?');
        return URLDecoder.decode(query < 0 ? last : last.substring(0, query), StandardCharsets.UTF_8);
    }
}
