package io.github.seah1006.careconnect.common.pagination;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("INV-API-004")
class CursorCodecProperties {
    // Synthetic, public test material only; never used by the application.
    private static final byte[] TEST_KEY = new byte[32];
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    private final CursorCodec codec = new CursorCodec(TEST_KEY);

    @Provide
    Arbitrary<Cursor> cursors() {
        Arbitrary<String> strings = Arbitraries.oneOf(
                Arbitraries.strings().all().ofMaxLength(1024),
                Arbitraries.of("", ":|,;./=", "가나다😀\u0000", "\uD800", "\uDC00"));
        Arbitrary<UUID> ids = Combinators.combine(Arbitraries.longs(), Arbitraries.longs()).as(UUID::new);
        return Combinators.combine(strings, ids).as(Cursor::new);
    }

    @Property(tries = 1000)
    void INV_API_004_roundTrip(@ForAll("cursors") Cursor cursor) {
        String encoded = codec.encode(cursor);
        assertThat(encoded).matches("[A-Za-z0-9_-]+");
        assertThat(codec.decode(encoded)).isEqualTo(cursor);
    }

    @Property(tries = 1000)
    void INV_API_004_anySingleCharacterTamperingIsRejected(@ForAll("cursors") Cursor cursor,
            @ForAll int position, @ForAll("replacementOffsets") int offset) {
        String encoded = codec.encode(cursor);
        int index = Math.floorMod(position, encoded.length());
        char replacement = ALPHABET.charAt((ALPHABET.indexOf(encoded.charAt(index)) + offset) % 64);
        String tampered = encoded.substring(0, index) + replacement + encoded.substring(index + 1);
        assertThatThrownBy(() -> codec.decode(tampered)).isInstanceOf(InvalidCursorException.class);
    }

    @Provide
    Arbitrary<Integer> replacementOffsets() {
        return Arbitraries.integers().between(1, 63);
    }

    @Property(tries = 1000)
    void INV_API_004_differentSigningKeyIsRejected(@ForAll("cursors") Cursor cursor,
            @ForAll byte changedByte, @ForAll int position) {
        byte[] differentKey = TEST_KEY.clone();
        differentKey[Math.floorMod(position, differentKey.length)] = (byte) (changedByte == 0 ? 1 : changedByte);
        String encoded = new CursorCodec(differentKey).encode(cursor);
        assertThatThrownBy(() -> codec.decode(encoded)).isInstanceOf(InvalidCursorException.class);
    }

    @Example
    void INV_API_004_rejectsMalformedEnvelopesAndNonCanonicalAliases() {
        for (String malformed : new String[] {"", "?", "a", "AAAA", " ", "💥"}) {
            assertThatThrownBy(() -> codec.decode(malformed)).isInstanceOf(InvalidCursorException.class);
        }
        assertThatThrownBy(() -> codec.decode(null)).isInstanceOf(InvalidCursorException.class);
        String encoded = codec.encode(new Cursor("", new UUID(0, 0)));
        assertThatThrownBy(() -> codec.decode(encoded + "==")).isInstanceOf(InvalidCursorException.class);
        // The final character has four unused bits for this envelope length.
        int last = encoded.length() - 1;
        char alias = ALPHABET.charAt(ALPHABET.indexOf(encoded.charAt(last)) + 1);
        assertThatThrownBy(() -> codec.decode(encoded.substring(0, last) + alias))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Example
    void INV_API_004_rejectsSignedButStructurallyInvalidPayloads() throws Exception {
        for (int length : new int[] {-1, 1, Integer.MAX_VALUE}) {
            byte[] payload = ByteBuffer.allocate(20).putInt(length).array();
            assertThatThrownBy(() -> codec.decode(signed(payload))).isInstanceOf(InvalidCursorException.class);
        }
        assertThatThrownBy(() -> codec.decode(signed(new byte[21])))
                .isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> codec.decode(signed(new byte[19])))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Example
    void INV_API_004_rejectsNullSigningKey() {
        assertThatThrownBy(() -> new CursorCodec(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Example
    void INV_API_004_rejectsSigningKeysShorterThan32Bytes() {
        for (int length : new int[] {0, 31}) {
            assertThatThrownBy(() -> new CursorCodec(new byte[length]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Example
    void INV_API_004_accepts32ByteSigningKey() {
        CursorCodec minimumKeyCodec = new CursorCodec(new byte[32]);
        Cursor cursor = new Cursor("synthetic", new UUID(0, 0));
        assertThat(minimumKeyCodec.decode(minimumKeyCodec.encode(cursor))).isEqualTo(cursor);
    }

    @Example
    void INV_API_004_constructorDefensivelyCopiesSigningKey() {
        byte[] mutableKey = TEST_KEY.clone();
        CursorCodec original = new CursorCodec(mutableKey);
        Cursor cursor = new Cursor("synthetic|😀", new UUID(Long.MIN_VALUE, Long.MAX_VALUE));
        String encoded = original.encode(cursor);
        Arrays.fill(mutableKey, (byte) 1);
        assertThat(original.encode(cursor)).isEqualTo(encoded);
        assertThat(original.decode(encoded)).isEqualTo(cursor);
    }

    private String signed(byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TEST_KEY, "HmacSHA256"));
        byte[] envelope = ByteBuffer.allocate(payload.length + 32).put(payload).put(mac.doFinal(payload)).array();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);
    }
}
