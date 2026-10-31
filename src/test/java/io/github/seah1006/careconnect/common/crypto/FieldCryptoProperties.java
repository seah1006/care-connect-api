package io.github.seah1006.careconnect.common.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.jqwik.api.*;
import static org.assertj.core.api.Assertions.*;

@Tag("INV-SENS-002")
@Tag("INV-SENS-003")
@Tag("INV-SENS-004")
class FieldCryptoProperties {
    @Provide Arbitrary<String> text() {
        return Arbitraries.oneOf(Arbitraries.just(""), Arbitraries.just("synthetic-\ud83d\ude00\ud800\udc00\ud55c\uae00"),
                Arbitraries.strings().withCharRange((char) 0, (char) 65535).ofMaxLength(4096));
    }
    @Provide Arbitrary<FieldSlot> slots() {
        return Combinators.combine(Arbitraries.longs(), Arbitraries.longs(),
                Arbitraries.strings().ofMaxLength(40), Arbitraries.strings().ofMaxLength(40))
                .as((a, b, table, column) -> new FieldSlot(new UUID(a, b), table, column, new UUID(b, a)));
    }
    @Property(tries = 1000)
    void allStringsAndKeyVersionsRoundTripAndRotate(@ForAll("text") String value,
            @ForAll long seed, @ForAll("slots") FieldSlot slot) {
        Random random = new Random(seed);
        Map<Integer, String> keys = new LinkedHashMap<>();
        for (int n = 1 + random.nextInt(5); keys.size() < n;) {
            byte[] key = new byte[32]; random.nextBytes(key);
            keys.put(1 + random.nextInt(255), Base64.getEncoder().encodeToString(key));
        }
        String configured = keys.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue())
                .collect(java.util.stream.Collectors.joining(","));
        var versions = new ArrayList<>(keys.keySet());
        int active = versions.get(random.nextInt(versions.size()));
        var cipher = new FieldCipher(new KeyRing(configured, "" + active, CryptoFixture.key(7, 48)));
        String sealed = cipher.seal(value, slot);
        assertThat(cipher.open(sealed, slot)).isEqualTo(value);
        assertThat(sealed).startsWith("v" + active + ".").doesNotContain("=");
        assertThat(cipher.needsRotation(sealed)).isFalse();
        int next = versions.get(random.nextInt(versions.size()));
        var rotated = new FieldCipher(new KeyRing(configured, "" + next, CryptoFixture.key(7, 48)));
        assertThat(rotated.open(sealed, slot)).isEqualTo(value);
        assertThat(rotated.needsRotation(sealed)).isEqualTo(active != next);
        for (int version : versions) {
            var reader = new FieldCipher(new KeyRing(configured, "" + version, CryptoFixture.key(7, 48)));
            assertThat(cipher.open(reader.seal(value, slot), slot)).isEqualTo(value);
        }
    }

    @Property(tries = 1000)
    void everyPayloadByteIsAuthenticatedAndEverySlotComponentIsBound(@ForAll("text") String value,
            @ForAll("slots") FieldSlot slot, @ForAll int position) {
        var cipher = new FieldCipher(CryptoFixture.keys());
        String sealed = cipher.seal(value, slot);
        byte[] payload = Base64.getUrlDecoder().decode(sealed.substring(3));
        payload[Math.floorMod(position, payload.length)] ^= 1;
        failed(() -> cipher.open("v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload), slot));
        for (FieldSlot other : List.of(
                new FieldSlot(new UUID(slot.orgId().getMostSignificantBits() ^ 1, slot.orgId().getLeastSignificantBits()), slot.table(), slot.column(), slot.rowId()),
                new FieldSlot(slot.orgId(), slot.table() + "_", slot.column(), slot.rowId()),
                new FieldSlot(slot.orgId(), slot.table(), slot.column() + "_", slot.rowId()),
                new FieldSlot(slot.orgId(), slot.table(), slot.column(), new UUID(slot.rowId().getMostSignificantBits() ^ 1, slot.rowId().getLeastSignificantBits())))) {
            assertThat(other.aad()).isNotEqualTo(slot.aad());
            failed(() -> cipher.open(sealed, other));
        }
        var foreign = new FieldCipher(new KeyRing("1:" + CryptoFixture.key(5, 32), "1", CryptoFixture.key(6, 32)));
        failed(() -> foreign.open(sealed, slot));
    }

    @Property(tries = 1000)
    void canonicalSlotEncodingHasNoCollisions(@ForAll("slots") FieldSlot a, @ForAll("slots") FieldSlot b) {
        if (!a.equals(b)) assertThat(a.aad()).isNotEqualTo(b.aad());
        var ambiguousA = new FieldSlot(a.orgId(), "a_b", "c", a.rowId());
        var ambiguousB = new FieldSlot(a.orgId(), "a", "b_c", a.rowId());
        assertThat(ambiguousA.aad()).isNotEqualTo(ambiguousB.aad());
    }

    @Property(tries = 1000)
    void freshNonceAndCiphertextHidePlaintext(@ForAll("slots") FieldSlot slot, @ForAll long seed) {
        String value = "synthetic-" + Long.toUnsignedString(seed) + "-\ud83d\ude00";
        var cipher = new FieldCipher(CryptoFixture.keys());
        String a = cipher.seal(value, slot), b = cipher.seal(value, slot);
        assertThat(a).isNotEqualTo(b).doesNotContain(value);
        byte[] payload = Base64.getUrlDecoder().decode(a.substring(3));
        assertThat(Arrays.copyOf(payload, 12)).isNotEqualTo(Arrays.copyOf(Base64.getUrlDecoder().decode(b.substring(3)), 12));
        assertThat(new String(payload, StandardCharsets.ISO_8859_1)).doesNotContain(value);
        assertThat(new String(payload, StandardCharsets.UTF_16BE)).doesNotContain(value);
    }

    @Property(tries = 1000)
    void indexIsDeterministicAndSeparated(@ForAll("slots") FieldSlot slot, @ForAll long seed) {
        String value = "synthetic-" + seed;
        var index = new BlindIndex(CryptoFixture.keys());
        String result = index.of(slot.orgId(), "phone", value);
        assertThat(result).matches("[0-9a-f]{64}").doesNotContain(value)
                .isEqualTo(index.of(slot.orgId(), "phone", value));
        assertThat(result).isNotEqualTo(index.of(new UUID(slot.orgId().getMostSignificantBits() ^ 1,
                slot.orgId().getLeastSignificantBits()), "phone", value));
        assertThat(result).isNotEqualTo(index.of(slot.orgId(), "another", value));
        assertThat(result).isNotEqualTo(index.of(slot.orgId(), "phone", value + "x"));
        assertThat(index.of(slot.orgId(), "a_b", "c")).isNotEqualTo(index.of(slot.orgId(), "a", "b_c"));
    }

    private static void failed(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isExactlyInstanceOf(FieldDecryptionFailed.class)
                .hasMessage("Field decryption failed").hasNoCause();
    }
}
