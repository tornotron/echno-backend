package org.tornotron.echno_backend.modules.documentingestion.extraction.contract;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The 19 contract terms read from the conditions of contract (design section 4.5). Each is a
 * {@code CONTRACT_TERM} candidate whose natural key is {@link #key()}, the term's name in snake case as
 * the corpus labels it, and whose payload is {@code {"value": ..., "clause": ...}} as printed.
 *
 * <p>These describe the main contract a tender leads to. They are kept in the module's own register and
 * never written to {@code contract_deduction_rule} or to sub-contract fields, which describe
 * sub-contracts (design section 4.5 and Q1).
 */
public enum ContractTerm {
    DURATION("duration", "Duration", "the time allowed to complete the whole of the works"),
    MOBILIZATION("mobilization", "Mobilization",
            "the mobilisation period, or the mobilisation advance and how it is recovered, as the contract states it"),
    EMD("emd", "EMD", "the earnest money deposit or bid security"),
    PERFORMANCE_SECURITY("performance_security", "Performance security",
            "the performance security or performance guarantee: amount or percentage, form and when it is due"),
    RETENTION("retention", "Retention", "the retention money held from each bill, and any limit on it"),
    ADVANCE("advance", "Advance",
            "any advance payment other than mobilisation, such as a secured or plant and machinery advance"),
    PAYMENT_CYCLE("payment_cycle", "Payment cycle", "how often the contractor may bill, such as monthly running bills"),
    PAYMENT_PERIOD("payment_period", "Payment period",
            "the time within which the employer pays a certified bill"),
    LD_RATE("ld_rate", "LD rate", "the rate of liquidated damages for delay"),
    LD_CAP("ld_cap", "LD cap", "the maximum of liquidated damages"),
    DLP("dlp", "DLP", "the defects liability period"),
    INSURANCE("insurance", "Insurance", "the insurances the contractor must take out"),
    ESCALATION("escalation", "Escalation", "price adjustment or escalation, or a statement that there is none"),
    SUBCONTRACTING("subcontracting", "Subcontracting", "whether and how the contractor may subcontract"),
    EXTENSION_OF_TIME("extension_of_time", "Extension of time",
            "when and how the time for completion may be extended"),
    VARIATION("variation", "Variation", "how variations or change orders are ordered and valued, and any limit"),
    DISPUTE_RESOLUTION("dispute_resolution", "Dispute resolution",
            "how disputes are settled, such as adjudication or arbitration, and where"),
    APPLICABLE_LAW("applicable_law", "Applicable law", "the law that governs the contract"),
    TERMINATION("termination", "Termination", "the grounds and notice for terminating the contract");

    /** The payload field holding the term as printed. */
    public static final String VALUE = "value";
    /** The payload field holding the clause number where the term is stated. */
    public static final String CLAUSE = "clause";

    /** Other spellings a model or a label may use for a term. */
    private static final Map<String, ContractTerm> ALIASES = Map.ofEntries(
            Map.entry("mobilisation", MOBILIZATION),
            Map.entry("mobilisation_advance", MOBILIZATION),
            Map.entry("mobilization_advance", MOBILIZATION),
            Map.entry("time_for_completion", DURATION),
            Map.entry("earnest_money_deposit", EMD),
            Map.entry("bid_security", EMD),
            Map.entry("performance_guarantee", PERFORMANCE_SECURITY),
            Map.entry("retention_money", RETENTION),
            Map.entry("liquidated_damages", LD_RATE),
            Map.entry("liquidated_damages_rate", LD_RATE),
            Map.entry("liquidated_damages_cap", LD_CAP),
            Map.entry("defects_liability_period", DLP),
            Map.entry("defect_liability_period", DLP),
            Map.entry("price_adjustment", ESCALATION),
            Map.entry("price_escalation", ESCALATION),
            Map.entry("sub_contracting", SUBCONTRACTING),
            Map.entry("subletting", SUBCONTRACTING),
            Map.entry("eot", EXTENSION_OF_TIME),
            Map.entry("variations", VARIATION),
            Map.entry("change_order", VARIATION),
            Map.entry("disputes", DISPUTE_RESOLUTION),
            Map.entry("arbitration", DISPUTE_RESOLUTION),
            Map.entry("governing_law", APPLICABLE_LAW));

    private final String key;
    private final String label;
    private final String meaning;

    ContractTerm(String key, String label, String meaning) {
        this.key = key;
        this.label = label;
        this.meaning = meaning;
    }

    public String key() {
        return key;
    }

    /** The term's name for a person. */
    public String label() {
        return label;
    }

    /** What the term covers, as the prompt puts it. */
    public String meaning() {
        return meaning;
    }

    public static Optional<ContractTerm> of(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String k = raw.strip().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (ContractTerm t : values()) {
            if (t.key.equals(k)) {
                return Optional.of(t);
            }
        }
        return Optional.ofNullable(ALIASES.get(k));
    }
}
