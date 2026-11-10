package org.tornotron.echno_backend.modules.documentingestion;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.tornotron.echno_backend.modules.documentingestion.web.DocumentIngestionBoqController;
import org.tornotron.echno_backend.modules.documentingestion.web.DocumentIngestionExtractionController;
import org.tornotron.echno_backend.modules.documentingestion.web.DocumentIngestionPackageController;
import org.tornotron.echno_backend.modules.documentingestion.web.DocumentIngestionReviewController;
import org.tornotron.echno_backend.modules.documentingestion.web.DocumentIngestionSettingsController;
import org.tornotron.echno_backend.modules.documentingestion.web.DocumentIngestionStatusController;

/**
 * Every document ingestion handler asks the access matrix for the step it performs, and nothing
 * else: reading needs read, uploading and driving the extraction need upload, applying and naming
 * the bill of quantities' target need apply, and the settings, budgets and the organization's LLM
 * calls need admin. Deciding candidates is open to any of the three review actions, since which
 * kinds a reviewer may decide is asked per candidate. A new handler fails here until it is given
 * its row.
 */
class DocumentIngestionGuardsTest {

    static final String REVIEW = "review";

    /** Keyed by controller and handler, as {@link #key} names them; valued by action. */
    static final Map<String, String> ACTION_BY_HANDLER = Map.ofEntries(
            entry("DocumentIngestionBoqController#detectSheets", "upload"),
            entry("DocumentIngestionBoqController#listSheets", "read"),
            entry("DocumentIngestionBoqController#extractSheet", "upload"),
            entry("DocumentIngestionBoqController#setBoqTarget", "apply"),
            entry("DocumentIngestionExtractionController#classification", "read"),
            entry("DocumentIngestionExtractionController#confirm", "upload"),
            entry("DocumentIngestionExtractionController#confirmAll", "upload"),
            entry("DocumentIngestionExtractionController#extractJobs", "read"),
            entry("DocumentIngestionExtractionController#retryFailed", "upload"),
            entry("DocumentIngestionExtractionController#llmCalls", "admin"),
            entry("DocumentIngestionExtractionController#packageLlmCalls", "read"),
            entry("DocumentIngestionExtractionController#spend", "read"),
            entry("DocumentIngestionExtractionController#setPackageBudget", "admin"),
            entry("DocumentIngestionExtractionController#setMonthlyBudget", "admin"),
            entry("DocumentIngestionPackageController#createPackage", "upload"),
            entry("DocumentIngestionPackageController#listPackages", "read"),
            entry("DocumentIngestionPackageController#getPackage", "read"),
            entry("DocumentIngestionPackageController#listDocuments", "read"),
            entry("DocumentIngestionPackageController#presign", "upload"),
            entry("DocumentIngestionPackageController#register", "upload"),
            entry("DocumentIngestionPackageController#getDocument", "read"),
            entry("DocumentIngestionPackageController#reparse", "upload"),
            entry("DocumentIngestionPackageController#downloadUrl", "read"),
            entry("DocumentIngestionReviewController#listCandidates", "read"),
            entry("DocumentIngestionReviewController#decide", REVIEW),
            entry("DocumentIngestionReviewController#apply", "apply"),
            entry("DocumentIngestionReviewController#provenance", "read"),
            entry("DocumentIngestionReviewController#archive", "upload"),
            entry("DocumentIngestionSettingsController#get", "read"),
            entry("DocumentIngestionSettingsController#setLlmReading", "admin"),
            entry("DocumentIngestionStatusController#status", "read"));

    @Test
    void everyHandlerNeedsTheStepItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(DocumentIngestionGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(ACTION_BY_HANDLER.keySet());
        ACTION_BY_HANDLER.forEach((handler, action) -> assertThat(guards.get(handler)).as(handler)
                .isEqualTo(guardFor(action)));
    }

    static String guardFor(String action) {
        if (action.equals(REVIEW)) {
            return "@access.can('document-ingestion', 'review') "
                    + "or @access.can('document-ingestion', 'review-quality') "
                    + "or @access.can('document-ingestion', 'review-drawings')";
        }
        return "@access.can('document-ingestion', '" + action + "')";
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Stream.of(DocumentIngestionBoqController.class, DocumentIngestionExtractionController.class,
                        DocumentIngestionPackageController.class, DocumentIngestionReviewController.class,
                        DocumentIngestionSettingsController.class, DocumentIngestionStatusController.class)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
