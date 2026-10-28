package io.github.seah1006.careconnect.common.pagination;

public final class InvalidCursorException extends RuntimeException {
    public InvalidCursorException() {
        super("Invalid pagination cursor");
    }
}
