package org.tornotron.echno_backend.subcontract.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class SubContractDto {
    private Long id;
    private String contractId;
    private String contractName;
    private String workDescription;
    private String scopeOfWork;

    private String contractorName;
    private String contractorContactPerson;
    private String contractorPhone;
    private String contractorEmail;
    private String contractorAddress;
    private String contractorGst;
    private String contractorPan;
    private String contractorLicense;

    private String type;
    private String status;

    private BigDecimal contractValue;
    private String currency;
    private BigDecimal mobilizationAdvance;
    private BigDecimal retentionPercentage;
    private BigDecimal totalPaid;
    private BigDecimal totalDue;
    private String paymentTerms;

    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDate actualCompletionDate;
    private BigDecimal completionPercentage;

    private Long projectId;
    private String projectName;

    private BigDecimal qualityRating;
    private BigDecimal timelinessRating;
    private BigDecimal safetyRating;
    private BigDecimal overallRating;

    private String insuranceProvider;
    private String insurancePolicyNumber;
    private LocalDate insuranceExpiry;

    private String bankName;
    private String bankAccountNumber;
    private String bankIfsc;

    private Long supervisorId;
    private Long accountManagerId;

    private String penaltyClause;
    private String warrantyPeriod;
    private String notes;

    private Long organizationId;
    private List<ContractMilestoneDto> milestones;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
