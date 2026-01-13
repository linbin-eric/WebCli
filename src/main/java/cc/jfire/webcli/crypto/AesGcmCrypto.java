package cc.jfire.webcli.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

public class AesGcmCrypto {
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final SecretKeySpec secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmCrypto(byte[] key) {
        if (key.length != 32) {
            throw new IllegalArgumentException("Key must be 256 bits (32 bytes)");
        }
        this.secretKey = new SecretKeySpec(key, "AES");
    }

    public byte[] encrypt(byte[] plaintext) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

        byte[] ciphertext = cipher.doFinal(plaintext);

        // IV + ciphertext
        byte[] result = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);

        return result;
    }

    public byte[] decrypt(byte[] encrypted) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        System.arraycopy(encrypted, 0, iv, 0, iv.length);

        byte[] ciphertext = new byte[encrypted.length - iv.length];
        System.arraycopy(encrypted, iv.length, ciphertext, 0, ciphertext.length);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);

        return cipher.doFinal(ciphertext);
    }

    public static byte[] generateKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
