package org.tornotron.echno_backend.billing;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;
import org.tornotron.echno_backend.billing.enums.QuotaPeriod;

import java.util.Map;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(exclude = {"plan", "feature"})
@ToString(exclude = {"plan", "feature"})
@Table(name = "plan_feature",
        uniqueConstraints = @UniqueConstraint(columnNames = {"plan_id", "feature_id"}),
        indexes = {
                @Index(name = "idx_plan_feature_plan", columnList = "plan_id"),
                @Index(name = "idx_plan_feature_feature", columnList = "feature_id")
        })
public class PlanFeature {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "feature_id", nullable = false)
    private Feature feature;

    @Builder.Default
    private Boolean enabled = true;

    private Long quotaLimit;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private QuotaPeriod quotaPeriod;

//    private Map<String , Object> metadata;

    public boolean isFeatureEnabled() {
        return enabled != null && enabled;
    }

    public boolean hasQuota() {
        return quotaLimit != null && quotaLimit > 0;
    }
}
