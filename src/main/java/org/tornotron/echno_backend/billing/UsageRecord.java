package org.tornotron.echno_backend.billing;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "usage_record", indexes = {
        @Index(name = "idx_usage_user_feature", columnList = "userId,featureId"),
        @Index(name = "idx_usage_subscription", columnList = "subscriptionId"),
        @Index(name = "idx_usage_period", columnList = "periodStart,periodEnd"),
        @Index(name = "idx_usage_feature_period", columnList = "featureId,periodStart,userId")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UsageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long subscriptionId;

    @Column(nullable = false)
    private Long featureId;

    @Column(nullable = false)
    @Builder.Default
    private Long usageAmount = 1L;

    @Column(nullable = false)
    private Instant periodStart;

    @Column(nullable = false)
    private Instant periodEnd;

    private String resourceId;

//    @Type(JsonBinaryType.class)
//    @Column(columnDefinition = "jsonb")
//    private Map<String, Object> metadata;
}