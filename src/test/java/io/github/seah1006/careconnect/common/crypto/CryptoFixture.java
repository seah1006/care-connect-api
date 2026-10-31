package io.github.seah1006.careconnect.common.crypto;

import java.util.*;
import java.util.function.BiConsumer;

public final class CryptoFixture {
    private CryptoFixture() {}
    public static String key(int value, int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return Base64.getEncoder().encodeToString(bytes);
    }
    public static KeyRing keys() { return new KeyRing("1:" + key(1, 32) + ",255:" + key(2, 32), "1", key(3, 32)); }
    public static void properties(BiConsumer<String, Object> set) {
        set.accept("security.field-encryption.keys", "1:" + key(1, 32));
        set.accept("security.field-encryption.active-version", "1");
        set.accept("security.field-index-key", key(3, 32));
    }
}
