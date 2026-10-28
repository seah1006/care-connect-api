package io.github.seah1006.careconnect.common.pagination;

import java.util.Objects;
import java.util.UUID;

public record Cursor(String sortKey, UUID id) {
    public Cursor {
        Objects.requireNonNull(sortKey, "sortKey");
        Objects.requireNonNull(id, "id");
    }
}
