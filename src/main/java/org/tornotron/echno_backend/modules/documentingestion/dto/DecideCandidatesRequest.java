package org.tornotron.echno_backend.modules.documentingestion.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record DecideCandidatesRequest(@NotEmpty @Size(max = 500) List<@Valid CandidateDecision> decisions) {
}
