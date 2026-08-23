package org.tornotron.echno_backend.subcontract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "Payload to create or fully update a subcontract with a contractor.")
@Data
public class SubContractCreationDto {

    @Schema(description = "Human-readable contract reference, if different from the generated id.", example = "SC-2026-0011")
    private String contractId;

    @Schema(description = "Name of the contract.", example = "Structural steel fabrication, Block C")
    @NotBlank
    private String contractName;

    @Schema(description = "Description of the work covered by the contract.", example = "Fabrication and erection of structural steel for Block C, ground plus four floors")
    private String workDescription;

    @Schema(description = "Detailed scope of work.", example = "Includes shop drawings, fabrication, transport and site erection; excludes painting")
    private String scopeOfWork;

    @Schema(description = "Name of the contracting firm or individual.", example = "Muthoot Structural Contractors")
    @NotBlank
    private String contractorName;

    @Schema(description = "Contact person at the contractor.", example = "Sunil Muthoot")
    private String contractorContactPerson;

    @Schema(description = "Contractor's phone number.", example = "9847098470")
    private String contractorPhone;

    @Schema(description = "Contractor's email address.", example = "sunil@muthootstructural.in")
    private String contractorEmail;

    @Schema(description = "Contractor's registered address.", example = "Industrial Estate, Kalamassery, Kochi")
    private String contractorAddress;

    @Schema(description = "Contractor's GST registration number.", example = "32AAECM1234F1Z5")
    private String contractorGst;

    @Schema(description = "Contractor's PAN.", example = "AAECM1234F")
    private String contractorPan;

    @Schema(description = "Contractor's trade or work license number.", example = "KL/STL/2024/0087")
    private String contractorLicense;

    @Schema(description = "Type of subcontract.", example = "LABOR_CONTRACT")
    private String type;

    @Schema(description = "Lifecycle status of the subcontract.", example = "ACTIVE")
    private String status;

    @Schema(description = "Total contract value in INR.", example = "3200000.00")
    private BigDecimal contractValue;

    @Schema(description = "Currency code for the contract value.", example = "INR")
    private String currency;

    @Schema(description = "Mobilization advance paid upfront, in INR.", example = "320000.00")
    private BigDecimal mobilizationAdvance;

    @Schema(description = "Retention percentage held back from each payment.", example = "5.00")
    private BigDecimal retentionPercentage;

    @Schema(description = "Total amount paid to the contractor so far, in INR.", example = "1200000.00")
    private BigDecimal totalPaid;

    @Schema(description = "Total amount still due to the contractor, in INR.", example = "2000000.00")
    private BigDecimal totalDue;

    @Schema(description = "Agreed payment terms.", example = "30 percent advance, balance against milestone certification")
    private String paymentTerms;

    @Schema(description = "Contract start date.", example = "2026-01-15")
    private LocalDate startDate;

    @Schema(description = "Contract end date.", example = "2026-06-30")
    private LocalDate endDate;

    @Schema(description = "Actual completion date, once finished.", example = "2026-07-05")
    private LocalDate actualCompletionDate;

    @Schema(description = "Percentage of work completed.", example = "45.00")
    private BigDecimal completionPercentage;

    @Schema(description = "Id of the project this subcontract belongs to.", example = "3")
    private Long projectId;

    @Schema(description = "Name of the project.", example = "Asset Homes Perumbavoor Phase 2")
    private String projectName;

    @Schema(description = "Quality rating out of 5.", example = "4.20")
    private BigDecimal qualityRating;

    @Schema(description = "Timeliness rating out of 5.", example = "3.80")
    private BigDecimal timelinessRating;

    @Schema(description = "Safety rating out of 5.", example = "4.50")
    private BigDecimal safetyRating;

    @Schema(description = "Overall rating out of 5.", example = "4.20")
    private BigDecimal overallRating;

    @Schema(description = "Name of the insurance provider covering the work.", example = "New India Assurance")
    private String insuranceProvider;

    @Schema(description = "Insurance policy number.", example = "NIA-CAR-2026-44210")
    private String insurancePolicyNumber;

    @Schema(description = "Insurance expiry date.", example = "2026-12-31")
    private LocalDate insuranceExpiry;

    @Schema(description = "Contractor's bank name for payments.", example = "Federal Bank")
    private String bankName;

    @Schema(description = "Contractor's bank account number.", example = "15920100012345")
    private String bankAccountNumber;

    @Schema(description = "Contractor's bank IFSC code.", example = "FDRL0001592")
    private String bankIfsc;

    @Schema(description = "Id of the employee supervising the contract on site.", example = "9")
    private Long supervisorId;

    @Schema(description = "Id of the employee handling the contract's payments.", example = "11")
    private Long accountManagerId;

    @Schema(description = "Penalty clause for delay or non-performance.", example = "0.5 percent of contract value per week of delay, capped at 5 percent")
    private String penaltyClause;

    @Schema(description = "Warranty or defect liability period.", example = "12 months from completion")
    private String warrantyPeriod;

    @Schema(description = "Free-text notes.", example = "Contractor previously worked on Phase 1 tower")
    private String notes;

    @Schema(description = "Payment milestones for the contract.")
    private List<ContractMilestoneDto> milestones;
}
