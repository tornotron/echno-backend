package org.tornotron.echno_backend.modules.vision.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.tornotron.echno_backend.common.module.GlobalReferenceData;

/**
 * One trained version of a {@link CvModel}: its weights, what it outputs, where it came from and
 * how it measured. Registered as {@link CvModelVersionStatus#DRAFT}; a person activates it after
 * reading its report. Observations keep their {@code model_version} string, so retiring a version
 * never touches history.
 */
@GlobalReferenceData("A version of a platform-wide model; every organization reads the same rows")
@Entity
@Table(name = "cv_model_versions")
@Getter
@Setter
@NoArgsConstructor
public class CvModelVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "model_id", nullable = false)
    private UUID modelId;

    @Column(nullable = false, length = 50)
    private String version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CvModelVersionStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<CvModelClass> classes;

    /** SPDX id of the licence the weights carry. Activation checks it against the allowlist. */
    @Column(nullable = false, length = 50)
    private String licence;

    @Column(name = "base_weights", nullable = false, length = 200)
    private String baseWeights;

    @Column(name = "weights_uri", nullable = false, length = 500)
    private String weightsUri;

    @Column(name = "weights_sha256", nullable = false, length = 64)
    private String weightsSha256;

    @Column(nullable = false, length = 30)
    private String runtime;

    @Column(name = "input_size", nullable = false)
    private int inputSize;

    @Column(name = "training_release", length = 50)
    private String trainingRelease;

    @Column(name = "training_run_uri", length = 500)
    private String trainingRunUri;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metrics;

    @Column(name = "eval_report_uri", length = 500)
    private String evalReportUri;

    @Column(name = "registered_by_id")
    private Long registeredById;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "retired_at")
    private LocalDateTime retiredAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
