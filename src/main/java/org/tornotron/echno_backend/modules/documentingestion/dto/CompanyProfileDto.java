package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.tornotron.echno_backend.modules.documentingestion.domain.CompanyProfile;

@Schema(description = "The company's profile for bidding, typed in by an admin. A blank field makes the matching "
        + "compliance matrix rows UNKNOWN, never GAP.")
public record CompanyProfileDto(
        @Schema(nullable = true, description = "Null until the profile is first saved.")
        UUID id,
        @Schema(nullable = true, example = "11")
        Integer yearsInBusiness,
        List<CompanyProfile.TurnoverYear> turnover,
        List<CompanyProfile.SimilarProject> similarProjects,
        List<CompanyProfile.EquipmentItem> equipment,
        List<CompanyProfile.PersonnelEntry> personnel,
        List<CompanyEvidenceDto> evidence,
        @Schema(nullable = true)
        Long updatedBy,
        @Schema(nullable = true)
        LocalDateTime updatedAt) {

    public static CompanyProfileDto of(CompanyProfile p, List<CompanyEvidenceDto> evidence) {
        if (p == null) {
            return new CompanyProfileDto(null, null, List.of(), List.of(), List.of(), List.of(), evidence, null, null);
        }
        return new CompanyProfileDto(p.getId(), p.getYearsInBusiness(), p.getTurnover(), p.getSimilarProjects(),
                p.getEquipment(), p.getPersonnel(), evidence, p.getUpdatedBy(), p.getUpdatedAt());
    }
}
