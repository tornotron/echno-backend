package org.tornotron.echno_backend.common.numbering;

import jakarta.persistence.*;
import lombok.Data;
import org.tornotron.echno_backend.organization.Organization;

import java.util.UUID;

@Entity
@Table(name = "document_sequence",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_document_sequence_org_type_year",
                columnNames = {"organization_id", "doc_type", "fiscal_year"}))
@Data
public class DocumentSequence {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @Column(name = "doc_type", nullable = false, length = 10)
    private String docType;

    @Column(name = "fiscal_year", nullable = false)
    private int fiscalYear;

    @Column(name = "next_value", nullable = false)
    private long nextValue;

    @Version
    private Long version;
}
