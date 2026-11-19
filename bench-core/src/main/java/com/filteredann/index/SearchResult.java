package com.filteredann.index;

import java.util.*;

public record SearchResult(List<Neighbor> neighbors, SearchStats stats) {
    public record Neighbor(int vectorId, double squaredL2) { }
    public static final Comparator<Neighbor> ORDER = Comparator.comparingDouble(Neighbor::squaredL2)
            .thenComparingInt(Neighbor::vectorId);
    public SearchResult { neighbors = List.copyOf(neighbors); }
}
