package io.github.seah1006.careconnect.common.crypto;

import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;

public final class BlindIndex {
    private final KeyRing keys;

    public BlindIndex(KeyRing keys) { this.keys = keys; }

    public String of(UUID orgId, String purpose, String normalizedValue) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(keys.indexKey());
            return HexFormat.of().formatHex(mac.doFinal(CryptoEncoding.fields(orgId.toString(), purpose, normalizedValue)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Blind index failed");
        }
    }

    public static String normalizePhone(String value) { return value.replaceAll("[^0-9]", ""); }
}
