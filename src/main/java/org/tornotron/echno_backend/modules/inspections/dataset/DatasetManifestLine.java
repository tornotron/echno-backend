package org.tornotron.echno_backend.modules.inspections.dataset;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One line of {@code export/<runKey>/manifest.jsonl} (#791).
 *
 * <p>The field names follow the dataset note's release manifest where the two overlap
 * ({@code object_key}, {@code source}, {@code source_ref}, {@code device}, {@code captured_at},
 * {@code annotation_version}, {@code licence}, {@code consent_org_id}, {@code anonymised}) so the
 * tooling can carry them through, and add what the tooling needs to build the raw session:
 * the inspection's trade and type, the spatial node and its breadcrumb, the upload time, and
 * the inspector-drawn rectangles as annotation version {@code a0}. {@code anonymised} is
 * always false here: blurring happens in the tooling before anything lands under {@code raw/}.
 * {@code sha256} is absent on purpose; the copy is server-side, so the bytes never pass
 * through the backend, and the tooling hashes what it anonymises.
 */
@JsonPropertyOrder({"evidence_id", "source", "source_ref", "attachment_id", "object_key",
        "source_object_key", "org_id", "consent_org_id", "project_id", "inspection_id",
        "spatial_node_id", "spatial_breadcrumb", "trade", "inspection_type", "inspection_category",
        "captured_at", "uploaded_at", "device", "content_type", "file_size", "annotation_version",
        "annotations", "licence", "anonymised", "run_id"})
public record DatasetManifestLine(
        @JsonProperty("evidence_id") String evidenceId,
        @JsonProperty("source") String source,
        @JsonProperty("source_ref") String sourceRef,
        @JsonProperty("attachment_id") Long attachmentId,
        @JsonProperty("object_key") String objectKey,
        @JsonProperty("source_object_key") String sourceObjectKey,
        @JsonProperty("org_id") Long orgId,
        @JsonProperty("consent_org_id") Long consentOrgId,
        @JsonProperty("project_id") Long projectId,
        @JsonProperty("inspection_id") UUID inspectionId,
        @JsonProperty("spatial_node_id") UUID spatialNodeId,
        @JsonProperty("spatial_breadcrumb") String spatialBreadcrumb,
        @JsonProperty("trade") String trade,
        @JsonProperty("inspection_type") String inspectionType,
        @JsonProperty("inspection_category") String inspectionCategory,
        @JsonProperty("captured_at") String capturedAt,
        @JsonProperty("uploaded_at") String uploadedAt,
        @JsonProperty("device") String device,
        @JsonProperty("content_type") String contentType,
        @JsonProperty("file_size") Long fileSize,
        @JsonProperty("annotation_version") String annotationVersion,
        @JsonProperty("annotations") List<Box> annotations,
        @JsonProperty("licence") String licence,
        @JsonProperty("anonymised") boolean anonymised,
        @JsonProperty("run_id") String runId) {

    /** The version the tooling imports inspector-drawn rectangles as. */
    public static final String ANNOTATION_VERSION_A0 = "a0";
    public static final String LICENCE_ORG_CONSENT = "org-consent";

    /** One {@code DefectPhotoAnnotation}: coordinates are fractions of the image in [0, 1]. */
    @JsonPropertyOrder({"shape", "x1", "y1", "x2", "y2", "label", "line_order"})
    public record Box(
            @JsonProperty("shape") String shape,
            @JsonProperty("x1") BigDecimal x1,
            @JsonProperty("y1") BigDecimal y1,
            @JsonProperty("x2") BigDecimal x2,
            @JsonProperty("y2") BigDecimal y2,
            @JsonProperty("label") String label,
            @JsonProperty("line_order") int lineOrder) {
    }
}
