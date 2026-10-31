package io.github.seah1006.careconnect.common.authz;

public final class AccessDenied extends RuntimeException {
    public AccessDenied() { super("Access denied"); }
}
