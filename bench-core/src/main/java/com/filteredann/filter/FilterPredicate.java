package com.filteredann.filter;

import com.filteredann.metadata.MetadataStore;
import java.util.Objects;

@FunctionalInterface
public interface FilterPredicate {
    boolean matches(MetadataStore metadata, int id);
    static FilterPredicate rankBelow(int threshold) { return (m, id) -> m.filterRanks()[id] < threshold; }
    static FilterPredicate category(int category) { return (m, id) -> m.categories()[id] == category; }
    static FilterPredicate region(int region) { return (m, id) -> m.regions()[id] == region; }
    default FilterPredicate and(FilterPredicate other) {
        Objects.requireNonNull(other);
        return (m, id) -> matches(m, id) && other.matches(m, id);
    }
}
