package io.github.seah1006.careconnect.common.crypto;

import java.nio.ByteBuffer;

final class CryptoEncoding {
    private CryptoEncoding() {}

    // Preserve every Java UTF-16 code unit, including unpaired surrogates.
    static byte[] text(String value) {
        var bytes = ByteBuffer.allocate(Math.multiplyExact(value.length(), 2));
        bytes.asCharBuffer().put(value);
        return bytes.array();
    }

    static byte[] fields(String... values) {
        byte[][] fields = new byte[values.length][];
        int size = 0;
        for (int i = 0; i < values.length; i++) {
            fields[i] = text(values[i]);
            size = Math.addExact(size, Math.addExact(4, fields[i].length));
        }
        var bytes = ByteBuffer.allocate(size);
        for (byte[] field : fields) bytes.putInt(field.length).put(field);
        return bytes.array();
    }
}
