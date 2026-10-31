package io.github.seah1006.careconnect.common.crypto;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

public final class FieldCipher {
    private final KeyRing keys;
    private final SecureRandom random = new SecureRandom();

    public FieldCipher(KeyRing keys) { this.keys = keys; }

    public String seal(String plaintext, FieldSlot slot) {
        if (plaintext == null) return null;
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.key(keys.activeVersion()), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(slot.aad());
            // Authenticate the version even when two configured versions share key bytes.
            cipher.updateAAD(new byte[] {(byte) keys.activeVersion()});
            byte[] encrypted = cipher.doFinal(CryptoEncoding.text(plaintext));
            byte[] payload = ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
            return "v" + keys.activeVersion() + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Field encryption failed");
        }
    }

    public String open(String sealed, FieldSlot slot) {
        if (sealed == null) return null;
        try {
            var parsed = parse(sealed);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keys.key(parsed.version()),
                    new GCMParameterSpec(128, parsed.payload(), 0, 12));
            cipher.updateAAD(slot.aad());
            cipher.updateAAD(new byte[] {(byte) parsed.version()});
            byte[] plaintext = cipher.doFinal(parsed.payload(), 12, parsed.payload().length - 12);
            return ByteBuffer.wrap(plaintext).asCharBuffer().toString();
        } catch (GeneralSecurityException | RuntimeException failure) {
            throw new FieldDecryptionFailed();
        }
    }

    public boolean needsRotation(String sealed) {
        return sealed != null && parse(sealed).version() != keys.activeVersion();
    }

    private Parsed parse(String sealed) {
        try {
            if (!sealed.matches("v[1-9][0-9]{0,2}\\.[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            int dot = sealed.indexOf('.');
            int version = Integer.parseInt(sealed.substring(1, dot));
            byte[] payload = Base64.getUrlDecoder().decode(sealed.substring(dot + 1));
            if (keys.key(version) == null || payload.length < 28
                    || !Base64.getUrlEncoder().withoutPadding().encodeToString(payload).equals(sealed.substring(dot + 1))) {
                throw new IllegalArgumentException();
            }
            return new Parsed(version, payload);
        } catch (RuntimeException failure) {
            throw new FieldDecryptionFailed();
        }
    }

    private record Parsed(int version, byte[] payload) {}
}
