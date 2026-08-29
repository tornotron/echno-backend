package org.tornotron.echno_backend.compliance.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code effectiveFrom} means, asserted where it is decided.
 *
 * <p>{@code createdAt} and {@code updatedAt} are Hibernate's to write and are not tested here.
 * {@code effectiveFrom} is the application's, and it is the one the sweep reads, so the two
 * things that have to hold are that a rule always has one and that nothing quietly moves it.
 */
class ComplianceRuleTest {

    /**
     * A rule the curator says nothing about is in force from when we learned of it. Leaving it
     * null is not an option, because the sweep would then have no way to tell a rule that is new
     * to us from one that has always been there, and the column is what this whole mechanism
     * rests on.
     */
    @Test
    void aNewRuleBecomesEffectiveWhenItIsCreated() {
        ComplianceRule rule = new ComplianceRule();
        LocalDateTime before = LocalDateTime.now();

        rule.defaultEffectiveFrom();

        assertThat(rule.getEffectiveFrom())
                .isNotNull()
                .isAfterOrEqualTo(before);
    }

    /**
     * A curator who dates a rule explicitly means it. Back-dating is how a rule that has been in
     * force for years is recorded without re-assessing every project against it, and overwriting
     * that with the load time would do exactly the thing the sweep is supposed to avoid.
     */
    @Test
    void anExplicitEffectiveFromIsNotOverwritten() {
        LocalDateTime stated = LocalDateTime.of(2024, 3, 1, 0, 0);
        ComplianceRule rule = new ComplianceRule();
        rule.setEffectiveFrom(stated);

        rule.defaultEffectiveFrom();

        assertThat(rule.getEffectiveFrom()).isEqualTo(stated);
    }

    /**
     * The distinction the sweep depends on. Correcting a rule's wording is not the rule coming
     * into force again, so nothing about editing a rule touches {@code effectiveFrom}: only a
     * deliberate re-dating does. A sweep keyed on {@code updatedAt} instead would treat a typo
     * fix across the catalogue as the whole catalogue changing and re-assess every approved
     * project in every tenant.
     */
    @Test
    void correctingTheWordingDoesNotChangeWhenTheRuleCameIntoForce() {
        LocalDateTime inForceSince = LocalDateTime.of(2026, 8, 21, 9, 30);
        ComplianceRule rule = new ComplianceRule();
        rule.setEffectiveFrom(inForceSince);
        rule.setDescription("Approval from the local planning authority");

        rule.setDescription("Approval from the local planning authority, before excavation");

        assertThat(rule.getEffectiveFrom()).isEqualTo(inForceSince);
    }
}
