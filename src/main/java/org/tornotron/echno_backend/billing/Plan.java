package org.tornotron.echno_backend.billing;


import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/**
 * A subscription tier an organization can subscribe to, bundling a set of features.
 *
 * <p>Carries monthly and annual pricing, currency, trial length, and a user cap, and is
 * identified by a unique {@code code}. Its {@link PlanFeature} entries bind each
 * {@link Feature} to this plan, including any per-feature quota. Only active, public plans
 * are offered for new subscriptions.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(exclude = "planFeatures")
@ToString(exclude = "planFeatures")
@Table(name = "plan", indexes = {
        @Index(name = "idx_plan_code", columnList = "code"),
        @Index(name = "idx_plan_active", columnList = "isActive")
})
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Column(precision = 10, scale = 2)
    private BigDecimal monthlyPrice;

    @Column(precision = 10, scale = 2)
    private BigDecimal annualPrice;

    @Column(length = 3)
    @Builder.Default
    private String currency = "INR";

    @Builder.Default
    private Boolean isActive = true;

    @Builder.Default
    private Boolean isPublic = true;

    @Builder.Default
    private Integer trialDays = 0;

    private Integer maxUsers;

    @Builder.Default
    private Integer sortOrder = 0;

    // Without @Builder.Default the builder ignores this initializer and leaves the collection
    // null, so addFeature throws on a builder-constructed plan and every caller has to remember
    // to pass an empty set of its own.
    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private Set<PlanFeature> planFeatures = new HashSet<>();

    public void addFeature(PlanFeature planFeature) {
        planFeatures.add(planFeature);
        planFeature.setPlan(this);
    }


    public void removeFeature(PlanFeature planFeature) {
        planFeatures.remove(planFeature);
        planFeature.setPlan(this);
    }

}
