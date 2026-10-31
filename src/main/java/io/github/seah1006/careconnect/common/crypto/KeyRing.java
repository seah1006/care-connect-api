package io.github.seah1006.careconnect.common.crypto;

import java.util.*;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public final class KeyRing {
    private final Map<Integer, SecretKey> keys;
    private final int activeVersion;
    private final SecretKey indexKey;

    public KeyRing(String configuredKeys, String configuredActiveVersion, String configuredIndexKey) {
        try {
            Map<Integer, SecretKey> parsed = new HashMap<>();
            for (String entry : configuredKeys.split(",", -1)) {
                String[] parts = entry.split(":", -1);
                if (parts.length != 2 || !parts[0].matches("[0-9]{1,3}")) throw new IllegalArgumentException();
                int version = Integer.parseInt(parts[0]);
                byte[] key = Base64.getDecoder().decode(parts[1]);
                if (version < 1 || version > 255 || key.length != 32
                        || parsed.putIfAbsent(version, new SecretKeySpec(key, "AES")) != null) {
                    throw new IllegalArgumentException();
                }
            }
            activeVersion = Integer.parseInt(configuredActiveVersion);
            if (!parsed.containsKey(activeVersion)) throw new IllegalArgumentException();
            byte[] index = Base64.getDecoder().decode(configuredIndexKey);
            if (index.length < 32 || parsed.values().stream().anyMatch(k -> Arrays.equals(k.getEncoded(), index))) {
                throw new IllegalArgumentException();
            }
            keys = Map.copyOf(parsed);
            indexKey = new SecretKeySpec(index, "HmacSHA256");
        } catch (RuntimeException failure) {
            // Do not retain a parser exception that could expose configured key material.
            throw new IllegalArgumentException("Invalid field key configuration");
        }
    }

    int activeVersion() { return activeVersion; }
    SecretKey key(int version) { return keys.get(version); }
    SecretKey indexKey() { return indexKey; }
}
