package org.dbplatform.controlplane.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.dbplatform.controlplane.config.DbpProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM encryption of INLINE credential secrets. The key is sha-256({@code DBP_MASTER_KEY}).
 * Without a configured master key a well-known development key is used and a WARN is logged.
 */
@Component
public class SecretCipher {
    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);
    private static final String DEV_KEY = "dbp-dev-master-key-change-me";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(DbpProperties props) {
        String master = props.getMasterKey();
        if (master == null || master.isBlank()) {
            log.warn("DBP_MASTER_KEY is not set: INLINE credential secrets are encrypted with the built-in development key. "
                    + "Set DBP_MASTER_KEY before storing real secrets.");
            master = DEV_KEY;
        }
        this.key = new SecretKeySpec(sha256(master.getBytes(StandardCharsets.UTF_8)), "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return "gcm1:" + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null) return null;
        if (!stored.startsWith("gcm1:")) throw new IllegalStateException("unknown secret encoding");
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(5));
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(all, IV_LEN, all.length - IV_LEN), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("decryption failed (was DBP_MASTER_KEY changed?)", e);
        }
    }

    public static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(String in) {
        byte[] d = sha256(in.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(64);
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
