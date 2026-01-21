package org.tornotron.echno_backend.billing;


import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

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

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
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
