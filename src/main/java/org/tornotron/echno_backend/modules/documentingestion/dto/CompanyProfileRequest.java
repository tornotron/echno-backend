package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.tornotron.echno_backend.modules.documentingestion.domain.CompanyProfile;

@Schema(description = "The whole company profile, replacing what was saved. Evidence files are added and removed "
        + "through their own calls and are kept. Leave a field empty when it is not known; it shows as UNKNOWN.")
public record CompanyProfileRequest(
        @Schema(nullable = true, example = "11") @Min(0) @Max(500)
        Integer yearsInBusiness,
        @Valid @Size(max = 50)
        List<CompanyProfile.TurnoverYear> turnover,
        @Valid @Size(max = 500)
        List<CompanyProfile.SimilarProject> similarProjects,
        @Valid @Size(max = 500)
        List<CompanyProfile.EquipmentItem> equipment,
        @Valid @Size(max = 500)
        List<CompanyProfile.PersonnelEntry> personnel) {
}
