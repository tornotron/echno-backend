package org.tornotron.echno_backend.modules.documentingestion.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionModule;
import org.tornotron.echno_backend.modules.documentingestion.apply.RiskRaiser;
import org.tornotron.echno_backend.modules.documentingestion.dto.ContractTermCoverageDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.ContractTermEntryDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.ObligationEntryDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.RaiseRiskRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.RaisedRiskDto;
import org.tornotron.echno_backend.modules.documentingestion.gate.DocumentIngestionGate;
import org.tornotron.echno_backend.modules.documentingestion.service.ContractRegisterService;
import org.tornotron.echno_backend.risk.RiskAccess;

/**
 * The contract registers (design section 4.5): the accepted contract terms, the coverage of the 19
 * terms with the pages searched for each one not found, the Contract Obligation Register, and the action
 * that raises a project risk from a term or an obligation.
 */
@RestController
@RequestMapping("/api/v1/document-ingestion")
@DocumentIngestionGate
@RequireSubscription(feature = DocumentIngestionModule.FEATURE_KEY)
@RequiredArgsConstructor
@Tag(name = "Document Ingestion")
public class DocumentIngestionContractController {

    private final ContractRegisterService registers;
    private final RiskRaiser riskRaiser;

    @GetMapping("/packages/{packageId}/contract-terms")
    @PreAuthorize(DocumentIngestionModule.READ_GUARD)
    @Operation(summary = "List a package's accepted contract terms",
            description = "One row per term applied from the general conditions, special conditions or contract data, "
                    + "as printed, with its clause. Written only by applying accepted CONTRACT_TERM candidates; "
                    + "provenance for each field is under GET /provenance with target type CONTRACT_TERM. These are "
                    + "the main contract's terms and are not written to sub-contracts or deduction rules.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The terms, by term key"),
            @ApiResponse(responseCode = "404", description = "No such package in the current tenant")
    })
    public List<ContractTermEntryDto> listContractTerms(@PathVariable UUID packageId) {
        return registers.terms(packageId);
    }

    @GetMapping("/packages/{packageId}/contract-terms/coverage")
    @PreAuthorize(DocumentIngestionModule.READ_GUARD)
    @Operation(summary = "Show each of the 19 contract terms as found, not found or not read",
            description = "A term found lists every value read for it, with its clause and the part it came from; "
                    + "where the special conditions or the contract data restate a general conditions term, both are "
                    + "listed and theirs is the proposed one. A term not found reads \"Not found, pages searched: "
                    + "...\", naming the pages read of each document.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The 19 terms and the parts read"),
            @ApiResponse(responseCode = "404", description = "No such package in the current tenant")
    })
    public ContractTermCoverageDto contractTermCoverage(@PathVariable UUID packageId) {
        return registers.coverage(packageId);
    }

    @GetMapping("/packages/{packageId}/obligations")
    @PreAuthorize(DocumentIngestionModule.READ_GUARD)
    @Operation(summary = "List the obligations found in a package's conditions of contract",
            description = "The Contract Obligation Register: obligations with a stated deadline, accepted and applied, "
                    + "each as printed with its clause, responsible party, trigger, deadline, required document and "
                    + "financial impact. It lists the obligations found, not every obligation of the contract. "
                    + "Provenance for each field is under GET /provenance with target type OBLIGATION.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of obligations, by clause"),
            @ApiResponse(responseCode = "404", description = "No such package in the current tenant")
    })
    public Page<ObligationEntryDto> listObligations(@PathVariable UUID packageId,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "50") int size) {
        return registers.obligations(packageId, page, size);
    }

    @PostMapping("/contract-terms/{termId}/risk")
    @PreAuthorize(RiskAccess.MANAGE_GUARD)
    @Operation(summary = "Raise a project risk from a contract term",
            description = "Adds a risk to the package's project register through the register's import, with the "
                    + "import reference set from the term's candidate, so raising twice gives one risk; the second "
                    + "call returns it with created false. The body is optional.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The risk was added"),
            @ApiResponse(responseCode = "200", description = "The risk was raised before; it is returned as it is"),
            @ApiResponse(responseCode = "400", description = "The package has no project, or a field failed validation"),
            @ApiResponse(responseCode = "403", description = "MODULE_DISABLED, or the caller cannot manage risks"),
            @ApiResponse(responseCode = "404", description = "No such contract term in the current tenant")
    })
    public ResponseEntity<RaisedRiskDto> raiseRiskFromTerm(@PathVariable UUID termId,
                                                           @Valid @RequestBody(required = false) RaiseRiskRequest req) {
        return respond(riskRaiser.raiseFromTerm(termId, req));
    }

    @PostMapping("/obligations/{obligationId}/risk")
    @PreAuthorize(RiskAccess.MANAGE_GUARD)
    @Operation(summary = "Raise a project risk from an obligation",
            description = "Adds a risk to the package's project register through the register's import, with the "
                    + "import reference set from the obligation's candidate, so raising twice gives one risk; the "
                    + "second call returns it with created false. The body is optional.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The risk was added"),
            @ApiResponse(responseCode = "200", description = "The risk was raised before; it is returned as it is"),
            @ApiResponse(responseCode = "400", description = "The package has no project, or a field failed validation"),
            @ApiResponse(responseCode = "403", description = "MODULE_DISABLED, or the caller cannot manage risks"),
            @ApiResponse(responseCode = "404", description = "No such obligation in the current tenant")
    })
    public ResponseEntity<RaisedRiskDto> raiseRiskFromObligation(@PathVariable UUID obligationId,
                                                                 @Valid @RequestBody(required = false) RaiseRiskRequest req) {
        return respond(riskRaiser.raiseFromObligation(obligationId, req));
    }

    private static ResponseEntity<RaisedRiskDto> respond(RaisedRiskDto raised) {
        return ResponseEntity.status(raised.created() ? HttpStatus.CREATED : HttpStatus.OK).body(raised);
    }
}
