package org.tornotron.echno_backend.finance.bank.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.tornotron.echno_backend.finance.ledger.domain.Account;
import org.tornotron.echno_backend.finance.ledger.domain.BaseEntity;

import java.util.UUID;

@Entity
@Table(name = "company_bank_accounts")
@Getter @Setter
@NoArgsConstructor
public class CompanyBankAccount extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "bank_name", nullable = false, length = 200)
    private String bankName;

    @Column(name = "account_number", nullable = false, length = 50)
    private String accountNumber;

    @Column(name = "account_holder_name", nullable = false, length = 200)
    private String accountHolderName;

    @Column(name = "ifsc_code", length = 20)
    private String ifscCode;

    @Column(name = "swift_code", length = 20)
    private String swiftCode;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Column(nullable = false)
    private boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ledger_account_id", nullable = false)
    private Account ledgerAccount;
}
