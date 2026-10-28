package io.github.seah1006.careconnect.common.pagination;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** A canonical, unpadded Base64url envelope containing a payload and its HMAC-SHA256. */
public final class CursorCodec {
    private static final int SIGNATURE_BYTES = 32;
    private static final int FIXED_PAYLOAD_BYTES = Integer.BYTES + 2 * Long.BYTES;
    private final SecretKeySpec key;

    public CursorCodec(byte[] key) {
        if (key == null || key.length < 32) {
            throw new IllegalArgumentException("Signing key must contain at least 32 bytes");
        }
        this.key = new SecretKeySpec(key, "HmacSHA256");
    }

    public String encode(Cursor cursor) {
        Objects.requireNonNull(cursor, "cursor");
        // Store Java UTF-16 code units verbatim, including any isolated surrogate.
        ByteBuffer payload = ByteBuffer.allocate(Math.addExact(FIXED_PAYLOAD_BYTES,
                Math.multiplyExact(cursor.sortKey().length(), Character.BYTES)));
        payload.putInt(cursor.sortKey().length());
        for (int i = 0; i < cursor.sortKey().length(); i++) {
            payload.putChar(cursor.sortKey().charAt(i));
        }
        payload.putLong(cursor.id().getMostSignificantBits());
        payload.putLong(cursor.id().getLeastSignificantBits());
        byte[] envelope = ByteBuffer.allocate(payload.capacity() + SIGNATURE_BYTES)
                .put(payload.array()).put(sign(payload.array())).array();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);
    }

    public Cursor decode(String encoded) {
        if (encoded == null) {
            throw new InvalidCursorException();
        }
        final byte[] envelope;
        try {
            envelope = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException ex) {
            throw new InvalidCursorException();
        }
        // Reject padding and aliases in unused Base64 bits: every character is authenticated.
        if (envelope.length < FIXED_PAYLOAD_BYTES + SIGNATURE_BYTES
                || !Base64.getUrlEncoder().withoutPadding().encodeToString(envelope).equals(encoded)) {
            throw new InvalidCursorException();
        }
        int payloadLength = envelope.length - SIGNATURE_BYTES;
        byte[] payload = Arrays.copyOf(envelope, payloadLength);
        byte[] signature = Arrays.copyOfRange(envelope, payloadLength, envelope.length);
        if (!MessageDigest.isEqual(sign(payload), signature)) {
            throw new InvalidCursorException();
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        int length = buffer.getInt();
        if (length < 0 || (payloadLength - FIXED_PAYLOAD_BYTES) % Character.BYTES != 0
                || length != (payloadLength - FIXED_PAYLOAD_BYTES) / Character.BYTES) {
            throw new InvalidCursorException();
        }
        StringBuilder sortKey = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sortKey.append(buffer.getChar());
        }
        return new Cursor(sortKey.toString(), new UUID(buffer.getLong(), buffer.getLong()));
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
        }
    }
}
