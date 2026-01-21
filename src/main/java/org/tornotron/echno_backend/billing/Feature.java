package org.tornotron.echno_backend.billing;

import jakarta.persistence.*;
import lombok.*;
import org.tornotron.echno_backend.billing.enums.FeatureType;

import java.util.HashSet;
import java.util.Set;

@Entity
@Builder
@Data
@NoArgsConstructor
@EqualsAndHashCode(exclude = "planFeatures")
@ToString(exclude = "planFeatures")
@Table(name = "feature", indexes = {
        @Index(name = "idx_feature_code", columnList = "code"),
        @Index(name = "idx_feature_category", columnList = "category")
})
@AllArgsConstructor
public class Feature {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FeatureType featureType;

    @Column(length = 50)
    private String category;

    @Builder.Default
    private Boolean isActive = true;

    @OneToMany(mappedBy = "feature")
    private Set<PlanFeature> planFeatures = new HashSet<>();


}
