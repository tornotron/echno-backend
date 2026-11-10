package org.tornotron.echno_backend.modules.documentingestion.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

/**
 * The company's own profile for bidding (design section 4.7), one row per organization, typed in by an
 * admin: turnover by financial year, similar projects with their values, years in business, the
 * equipment list and the personnel list. Nothing is read into it from documents. A list left empty, or
 * years in business left blank, makes the matching matrix rows {@code UNKNOWN}, never {@code GAP}.
 */
@Entity
@Table(name = "doc_ingest_company_profile")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class CompanyProfile implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "years_in_business")
    private Integer yearsInBusiness;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "turnover", columnDefinition = "jsonb", nullable = false)
    private List<TurnoverYear> turnover = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "similar_projects", columnDefinition = "jsonb", nullable = false)
    private List<SimilarProject> similarProjects = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "equipment", columnDefinition = "jsonb", nullable = false)
    private List<EquipmentItem> equipment = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "personnel", columnDefinition = "jsonb", nullable = false)
    private List<PersonnelEntry> personnel = new ArrayList<>();

    @Column(name = "updated_by")
    private Long updatedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Schema(description = "The company's turnover in one financial year, in rupees.")
    public record TurnoverYear(
            @Schema(description = "The financial year, as 2025-26 or 2025.", example = "2025-26")
            @NotBlank @Pattern(regexp = "\\d{4}(-\\d{2}|-\\d{4})?") String financialYear,
            @Schema(description = "Rupees.", example = "1320000000")
            @NotNull @DecimalMin("0") BigDecimal amount) {
    }

    @Schema(description = "A completed work the company counts as similar. Whether it is similar to the tender's work "
            + "is the admin's judgement.")
    public record SimilarProject(
            @Schema(example = "Hillcrest school block") @NotBlank @Size(max = 300) String name,
            @Schema(nullable = true, example = "Northfield Municipal Council") @Size(max = 300) String client,
            @Schema(nullable = true, description = "The work's value in rupees. Blank counts as unknown, never as too "
                    + "small.", example = "420000000") @DecimalMin("0") BigDecimal value,
            @Schema(nullable = true, description = "The year the work was completed. Blank counts as unknown when a "
                    + "tender counts works over a period.", example = "2024") @Min(1900) @Max(2200) Integer completedYear,
            @Schema(nullable = true) @Size(max = 500) String description) {
    }

    @Schema(description = "A machine or plant item the company owns or has access to.")
    public record EquipmentItem(
            @Schema(example = "Tower crane") @NotBlank @Size(max = 300) String name,
            @Schema(nullable = true, example = "2") @Min(0) Integer quantity,
            @Schema(nullable = true, example = "Owned, 8 t capacity") @Size(max = 500) String details) {
    }

    @Schema(description = "A member of staff the company can deploy.")
    public record PersonnelEntry(
            @Schema(example = "Project manager") @NotBlank @Size(max = 200) String role,
            @Schema(nullable = true) @Size(max = 200) String name,
            @Schema(nullable = true, example = "B.E. Civil") @Size(max = 300) String qualification,
            @Schema(nullable = true, example = "12") @Min(0) @Max(80) Integer experienceYears) {
    }
}
