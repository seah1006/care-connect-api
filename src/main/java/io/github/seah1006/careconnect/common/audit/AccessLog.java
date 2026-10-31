package io.github.seah1006.careconnect.common.audit;

@FunctionalInterface
public interface AccessLog {
    void record(AccessEvent event);
}
