package dev.qlcstream.system;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationSecretCipherTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void survivesRestartAndUsesUniqueNonces() {
        var cipher = new IntegrationSecretCipher(KEY);
        var stored = cipher.encrypt("tmdb", "fictional-token");
        assertFalse(stored.contains("fictional-token"));
        assertNotEquals(stored, cipher.encrypt("tmdb", "fictional-token"));
        assertEquals("fictional-token", new IntegrationSecretCipher(KEY).decrypt("tmdb", stored));
    }

    @Test
    void rejectsWrongKeyTamperingAndMovingCiphertextToAnotherIntegration() {
        var cipher = new IntegrationSecretCipher(KEY);
        var stored = cipher.encrypt("tmdb", "fictional-token");
        var other = new byte[32];
        other[0] = 1;
        assertThrows(IllegalStateException.class, () -> new IntegrationSecretCipher(
                Base64.getEncoder().encodeToString(other)).decrypt("tmdb", stored));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("prowlarr", stored));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("tmdb", stored + "broken"));
    }

    @Test
    void rejectsMissingAndInvalidKeysWithoutEchoingTheirContents() {
        for (var value : new String[]{"", "private-invalid-key", Base64.getEncoder().encodeToString(new byte[16])}) {
            var failure = assertThrows(IllegalStateException.class, () -> new IntegrationSecretCipher(value));
            assertFalse(failure.getMessage().contains("private-invalid-key"));
        }
    }
}
