package org.tornotron.echno_backend.finance.ledger.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;


@Entity
@Table(name = "journal_entry_lines",
      indexes = {
              @Index(name = "idx_jel_account", columnList = "account_id"),
              @Index(name = "idx_jel_journal", columnList = "journal_entry_id")
      })
@Getter @Setter
@NoArgsConstructor
public class JournalEntryLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_entry_id", nullable = false)
    private JournalEntry journalEntry;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal debit = BigDecimal.ZERO;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal credit = BigDecimal.ZERO;

    @Column(length = 500)
    private String narration;

    @Column(name = "line_order", nullable = false)
    private int lineOrder;
}
