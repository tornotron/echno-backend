package org.tornotron.echno_backend.finance.ledger.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.tornotron.echno_backend.finance.ledger.JournalStatus;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "journal_entries",
       indexes = {
            @Index(name = "idx_je_entry_date", columnList = "entry_date"),
            @Index(name = "idx_je_status", columnList = "status"),
            @Index(name = "idx_je_reference", columnList = "reference")
       },
       uniqueConstraints = @UniqueConstraint(name = "uk_je_number",columnNames = "entry_number"))
@Getter @Setter
@NoArgsConstructor
public class JournalEntry extends BaseEntity{

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "entry_number", nullable = false, length = 30)
    private String entryNumber;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(length = 100)
    private String reference;     // e.g. invoice number, payment number

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JournalStatus status;

    @Column(name = "reversed_by_entry_id")
    private UUID reversedByEntryId;

    @Column(name = "reverses_entry_id")
    private UUID reversesEntryId;

    @Column(name = "source_type", length = 30)
    private String sourceType;    // INVOICE, PAYMENT, MANUAL, REVERSAL

    @Column(name = "source_id")
    private UUID sourceId;

    @OneToMany(mappedBy = "journalEntry", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<JournalEntryLine> lines = new ArrayList<>();

    public void addLine(JournalEntryLine line) {
        line.setJournalEntry(this);
        this.lines.add(line);
    }
}
