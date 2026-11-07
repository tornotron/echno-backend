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
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.module.GlobalReferenceData;

/** A model family: one inspection problem, with its own class list and its own versions. */
@GlobalReferenceData("The model registry is a platform-wide catalogue: every organization's images run on the same "
        + "versions, and producers in any organization are checked against the same rows")
@Entity
@Table(name = "cv_models")
@Getter
@Setter
@NoArgsConstructor
public class CvModel {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The stable name producers send as {@code modelName}, for example {@code ppe}. */
    @Column(name = "model_key", nullable = false, length = 50, unique = true)
    private String modelKey;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CvModelTask task;

    @Enumerated(EnumType.STRING)
    @Column(name = "model_family", nullable = false, length = 20)
    private CvModelFamily family;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
