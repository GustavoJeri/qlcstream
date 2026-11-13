package dev.qlcstream.system;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** The caller provisions the key outside the database; failures never expose input. */
final class IntegrationSecretCipher {
    private final byte[] key;

    IntegrationSecretCipher(String encodedKey) {
        try {
            key = Base64.getDecoder().decode(encodedKey);
            if (key.length != 32) throw new IllegalArgumentException();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Integration settings require a Base64 encoded 32-byte key.");
        }
    }

    String encrypt(String integration, String value) {
        try {
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            var cipher = cipher(Cipher.ENCRYPT_MODE, integration, nonce);
            return Base64.getEncoder().encodeToString(nonce) + "."
                    + Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to protect integration settings.");
        }
    }

    boolean sameKey(IntegrationSecretCipher other) {
        return java.security.MessageDigest.isEqual(key, other.key);
    }

    String decrypt(String integration, String value) {
        try {
            var parts = value.split("\\.", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            byte[] nonce = Base64.getDecoder().decode(parts[0]);
            if (nonce.length != 12) throw new IllegalArgumentException();
            return new String(cipher(Cipher.DECRYPT_MODE, integration, nonce)
                    .doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to recover integration settings; restore the matching key and database.");
        }
    }

    private Cipher cipher(int mode, String integration, byte[] nonce) throws Exception {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("qlcstream:integration:v1:" + integration).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
