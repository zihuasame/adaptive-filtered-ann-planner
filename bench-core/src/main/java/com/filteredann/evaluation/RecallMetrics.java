package com.filteredann.evaluation;

import java.util.*;
import com.filteredann.index.SearchResult.Neighbor;

public final class RecallMetrics {
    private RecallMetrics() { }
    public static OptionalDouble recall(List<Neighbor> retrieved, List<Neighbor> groundTruth) {
        Set<Integer> expected = new HashSet<>(), actual = new HashSet<>();
        for (Neighbor n : groundTruth) expected.add(n.vectorId());
        if (expected.size() != groundTruth.size()) throw new IllegalArgumentException("Duplicate GT IDs");
        if (expected.isEmpty()) return OptionalDouble.empty();
        for (Neighbor n : retrieved) actual.add(n.vectorId());
        actual.retainAll(expected);
        return OptionalDouble.of((double) actual.size() / expected.size());
    }
}
