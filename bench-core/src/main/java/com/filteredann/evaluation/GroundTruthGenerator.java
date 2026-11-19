package com.filteredann.evaluation;

import com.filteredann.dataset.Dataset;
import com.filteredann.filter.FilterBitmap;
import com.filteredann.index.*;

public final class GroundTruthGenerator {
    private final BruteForceIndex exact;
    private final int count;
    public GroundTruthGenerator(Dataset dataset) { exact = new BruteForceIndex(dataset); count = dataset.count(); }
    public SearchResult global(float[] query, int k) { return exact.search(query, FilterBitmap.all(count), k); }
    public SearchResult filtered(float[] query, FilterBitmap filter, int k) { return exact.search(query, filter, k); }
}
