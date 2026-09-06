package org.tornotron.echno_backend.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.category.CategoryNormalizer;
import org.tornotron.echno_backend.common.dto.AttachmentOwner;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Case folding that decides what a row is keyed by, or which folder a file lands in, must be a
 * property of the value rather than of the JVM the value happened to pass through.
 *
 * <p>Turkish is the case that shows it. Under {@code tr-TR}, {@code "I".toLowerCase()} is the
 * dotless {@code "ı"} and {@code "i".toUpperCase()} is the dotted {@code "İ"}, so the same input
 * normalises to two different strings depending on the locale the JVM starts in. A container
 * inheriting a different locale after a base-image change is enough to switch it.
 *
 * <p>Both values below are stored, and both are then matched against: the category's normalised
 * name is the uniqueness key, and the attachment folder is the object-store key prefix. A value
 * written under one locale and looked up under another does not agree with itself.
 *
 * <p>The category case is the sharper of the two, because the lowering is followed by a
 * {@code [^a-z0-9\s]} strip. The dotless {@code ı} is not in that set, so it is not merely folded
 * differently, it is deleted: "Iron" normalises to "ron".
 */
class LocaleIndependentNormalizationTest {

    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    private Locale original;

    @BeforeEach
    void useTurkishDefault() {
        original = Locale.getDefault();
        Locale.setDefault(TURKISH);
    }

    @AfterEach
    void restoreDefault() {
        Locale.setDefault(original);
    }

    @Test
    void aCategoryNameNormalisesTheSameWhateverTheJvmLocale() {
        assertThat(CategoryNormalizer.normalize("Iron Fittings")).isEqualTo("iron fittings");
    }

    @Test
    void aCategoryNameKeepsItsLettersWhateverTheJvmLocale() {
        // The dotless i the Turkish lowering produces is outside [a-z0-9\s] and is stripped,
        // so the failure here is a lost character rather than a differently cased one.
        assertThat(CategoryNormalizer.normalize("MIX")).isEqualTo("mix");
    }

    @Test
    void anAttachmentFolderIsTheSameWhateverTheJvmLocale() {
        assertThat(AttachmentOwner.of("INSPECTION_EVIDENCE", 1L).folder())
                .isEqualTo("inspection");
    }

    @Test
    void anAttachmentFolderForAnIssueIsTheSameWhateverTheJvmLocale() {
        assertThat(AttachmentOwner.of("ISSUE_ATTACHMENTS", 1L).folder())
                .isEqualTo("issue");
    }
}
