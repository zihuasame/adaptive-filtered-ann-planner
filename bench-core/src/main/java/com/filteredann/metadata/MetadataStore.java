package com.filteredann.metadata;

public record MetadataStore(int[] filterRanks, int[] categories, int[] regions) {
    public MetadataStore {
        if (filterRanks.length != categories.length || categories.length != regions.length) {
            throw new IllegalArgumentException("Metadata length mismatch");
        }
        boolean[] seen = new boolean[filterRanks.length];
        for (int id = 0; id < filterRanks.length; id++) {
            int rank = filterRanks[id];
            if (rank < 0 || rank >= seen.length || seen[rank]) throw new IllegalArgumentException("Ranks must be a permutation");
            seen[rank] = true;
            if (categories[id] < 0 || categories[id] >= 16 || regions[id] < 0 || regions[id] >= 8) {
                throw new IllegalArgumentException("Metadata cardinality exceeded");
            }
        }
    }
    public int count() { return filterRanks.length; }
}
