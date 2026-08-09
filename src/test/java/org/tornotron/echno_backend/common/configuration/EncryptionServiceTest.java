package org.tornotron.echno_backend.common.configuration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link EncryptionService}. Pure (no Spring context): the key is
 * a constructor argument, so the service is instantiated directly. Verifies the
 * AES/GCM round-trip, per-encryption random IV, and authenticated-decryption
 * guarantees the app relies on for encrypting sensitive fields at rest.
 */
class EncryptionServiceTest {

    // Fixed Base64-encoded 256-bit AES keys for deterministic tests.
    private static final String KEY_A = "+EOVKh6q7e3H0zeLYr9Cny32oRAGU9V1IZ37tSykzu4=";
    private static final String KEY_B = "/unPw5cnTt285okMEfF4exfc+jQAj91eoI1aHKHCvww=";

    private final EncryptionService encryptionService = new EncryptionService(KEY_A);

    @Test
    void encryptThenDecryptRoundTrips() {
        String plain = "sensitive-value-123";
        String encrypted = encryptionService.encrypt(plain);

        assertNotEquals(plain, encrypted, "ciphertext must not equal the plaintext");
        assertEquals(plain, encryptionService.decrypt(encrypted));
    }

    @Test
    void encryptUsesARandomIvSoRepeatedEncryptionsDiffer() {
        String plain = "same-input";

        assertNotEquals(
                encryptionService.encrypt(plain),
                encryptionService.encrypt(plain),
                "each encryption must use a fresh random IV");
    }

    @Test
    void decryptWithADifferentKeyFails() {
        String encrypted = encryptionService.encrypt("secret");
        EncryptionService other = new EncryptionService(KEY_B);

        assertThrows(RuntimeException.class, () -> other.decrypt(encrypted));
    }

    @Test
    void tamperedCiphertextFailsAuthentication() {
        String encrypted = encryptionService.encrypt("secret");
        char[] chars = encrypted.toCharArray();
        int i = chars.length - 3; // flip a character inside the ciphertext/tag
        chars[i] = (chars[i] == 'A') ? 'B' : 'A';

        assertThrows(RuntimeException.class,
                () -> encryptionService.decrypt(new String(chars)));
    }
}
