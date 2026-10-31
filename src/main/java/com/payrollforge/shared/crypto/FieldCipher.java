package com.payrollforge.shared.crypto;

import com.payrollforge.shared.config.PayrollForgeProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM for individual columns (bank account, PAN). Each value gets a fresh 12-byte nonce, stored in front
 * of the ciphertext; the column name is bound in as associated data so a value cannot be moved to another column
 * and still decrypt. Key rotation is not implemented.
 */
@Component
public class FieldCipher {

    private static final int NONCE = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public FieldCipher(PayrollForgeProperties props) {
        String b64 = props.crypto() == null ? null : props.crypto().fieldKey();
        if (b64 == null || b64.isBlank()) {
            throw new IllegalStateException("payrollforge.crypto.field-key is required (base64, 32 bytes)");
        }
        byte[] raw = Base64.getDecoder().decode(b64.trim());
        if (raw.length != 32) {
            throw new IllegalStateException("payrollforge.crypto.field-key must decode to 32 bytes");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String column, String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] nonce = new byte[NONCE];
            random.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(column.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(NONCE + ct.length).put(nonce).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    public String decrypt(String column, String stored) {
        if (stored == null) {
            return null;
        }
        if (!stored.startsWith("v1:")) {
            throw new IllegalStateException("unknown ciphertext format");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(3));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, NONCE));
            c.updateAAD(column.getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(all, NONCE, all.length - NONCE), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decryption failed", e);
        }
    }

    /** "XXXXXX1234": what the API shows instead of the value. */
    public static String mask(String plain) {
        if (plain == null) {
            return null;
        }
        int keep = Math.min(4, plain.length());
        return "X".repeat(plain.length() - keep) + plain.substring(plain.length() - keep);
    }
}
