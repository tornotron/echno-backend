package org.tornotron.echno_backend.modules.documentingestion;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.tornotron.echno_backend.modules.inspections.api.ChecklistTemplatePort;
import org.tornotron.echno_backend.modules.documentingestion.web.DocumentIngestionBoqController;
import org.tornotron.echno_backend.modules.documentingestion.web.DocumentIngestionContractController;
import org.tornotron.echno_backend.modules.documentingestion.web.DocumentIngestionDrawingController;
import org.tornotron.echno_backend.modules.documentingestion.web.DocumentIngestionExtractionController;
import org.tornotron.echno_backend.modules.documentingestion.web.DocumentIngestionPackageController;
import org.tornotron.echno_backend.modules.documentingestion.web.DocumentIngestionQualificationController;
import org.tornotron.echno_backend.modules.documentingestion.web.DocumentIngestionQualityController;
import org.tornotron.echno_backend.modules.documentingestion.web.DocumentIngestionReviewController;
import org.tornotron.echno_backend.modules.documentingestion.web.DocumentIngestionRevisionController;
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
            entry("DocumentIngestionPackageController#pageImage", "read"),
            entry("DocumentIngestionPackageController#getTenderProfile", "read"),
            entry("DocumentIngestionContractController#listContractTerms", "read"),
            entry("DocumentIngestionContractController#contractTermCoverage", "read"),
            entry("DocumentIngestionContractController#listObligations", "read"),
            entry("DocumentIngestionDrawingController#listDrawings", "read"),
            entry("DocumentIngestionDrawingController#listDrawingRevisions", "read"),
            entry("DocumentIngestionDrawingController#titleBlockImage", "read"),
            entry("DocumentIngestionQualificationController#getCompanyProfile", "read"),
            entry("DocumentIngestionQualificationController#saveCompanyProfile", "admin"),
            entry("DocumentIngestionQualificationController#presignEvidence", "admin"),
            entry("DocumentIngestionQualificationController#registerEvidence", "admin"),
            entry("DocumentIngestionQualificationController#deleteEvidence", "admin"),
            entry("DocumentIngestionQualificationController#evidenceDownload", "read"),
            entry("DocumentIngestionQualificationController#listQualificationRequirements", "read"),
            entry("DocumentIngestionQualificationController#setRequirementStatus", "apply"),
            entry("DocumentIngestionQualificationController#complianceMatrix", "read"),
            entry("DocumentIngestionQualityController#listForPackage", "read"),
            entry("DocumentIngestionQualityController#listForProject", "read"),
            entry("DocumentIngestionRevisionController#markRevision", "upload"),
            entry("DocumentIngestionRevisionController#diff", "upload"),
            entry("DocumentIngestionRevisionController#chooseOnConflict", REVIEW),
            entry("DocumentIngestionReviewController#listCandidates", "read"),
            entry("DocumentIngestionReviewController#decide", REVIEW),
            entry("DocumentIngestionReviewController#apply", "apply"),
            entry("DocumentIngestionReviewController#provenance", "read"),
            entry("DocumentIngestionReviewController#archive", "upload"),
            entry("DocumentIngestionSettingsController#get", "read"),
            entry("DocumentIngestionSettingsController#setLlmReading", "admin"),
            entry("DocumentIngestionStatusController#status", "read"));

    /**
     * Handlers that write into another area ask what that area's own endpoint asks: raising a risk
     * from a term or an obligation is a risk create, and copying ITP rows into a trade's checklist
     * template may create the template or add a revision to it.
     */
    static final Map<String, String> TARGET_GUARD_BY_HANDLER = Map.ofEntries(
            entry("DocumentIngestionContractController#raiseRiskFromTerm", "@access.can('compliance', 'create')"),
            entry("DocumentIngestionContractController#raiseRiskFromObligation",
                    "@access.can('compliance', 'create')"),
            entry("DocumentIngestionQualityController#copyToTemplate", ChecklistTemplatePort.DEFINE_GUARD));

    @Test
    void everyHandlerNeedsTheStepItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(DocumentIngestionGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        Set<String> expected = new HashSet<>(ACTION_BY_HANDLER.keySet());
        expected.addAll(TARGET_GUARD_BY_HANDLER.keySet());
        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(expected);
        ACTION_BY_HANDLER.forEach((handler, action) -> assertThat(guards.get(handler)).as(handler)
                .isEqualTo(guardFor(action)));
        TARGET_GUARD_BY_HANDLER.forEach((handler, guard) -> assertThat(guards.get(handler)).as(handler)
                .isEqualTo(guard));
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
                        DocumentIngestionSettingsController.class, DocumentIngestionStatusController.class,
                        DocumentIngestionContractController.class, DocumentIngestionDrawingController.class,
                        DocumentIngestionQualificationController.class, DocumentIngestionQualityController.class,
                        DocumentIngestionRevisionController.class)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
