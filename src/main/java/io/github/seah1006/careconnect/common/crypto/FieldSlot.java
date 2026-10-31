package io.github.seah1006.careconnect.common.crypto;

import java.util.Objects;
import java.util.UUID;

public record FieldSlot(UUID orgId, String table, String column, UUID rowId) {
    public FieldSlot {
        Objects.requireNonNull(orgId);
        Objects.requireNonNull(table);
        Objects.requireNonNull(column);
        Objects.requireNonNull(rowId);
    }

    byte[] aad() {
        return CryptoEncoding.fields(orgId.toString(), table, column, rowId.toString());
    }
}
