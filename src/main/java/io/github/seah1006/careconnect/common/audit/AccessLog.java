package io.github.seah1006.careconnect.common.audit;

import java.util.List;

@FunctionalInterface
public interface AccessLog {
    default void record(AccessEvent event) { recordAll(List.of(event)); }

    void recordAll(List<AccessEvent> events);
}
