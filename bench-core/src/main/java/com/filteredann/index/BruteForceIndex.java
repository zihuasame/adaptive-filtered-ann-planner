package com.filteredann.index;

import com.filteredann.dataset.Dataset;
import com.filteredann.filter.FilterBitmap;
import java.util.*;
import com.filteredann.index.SearchResult.Neighbor;

public final class BruteForceIndex implements SearchIndex {
    private final Dataset dataset;
    public BruteForceIndex(Dataset dataset) { this.dataset = Objects.requireNonNull(dataset); }
    @Override public String name() { return "exact-brute-force-squared-l2"; }
    @Override public SearchResult search(float[] query, FilterBitmap filter, int k) {
        if (k <= 0) throw new IllegalArgumentException("K must be positive");
        if (query == null || query.length != dataset.dimensions()) throw new IllegalArgumentException("Query dimension mismatch");
        for (float value : query) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite query");
        if (filter == null || filter.length() != dataset.count()) throw new IllegalArgumentException("Bitmap length mismatch");
        long start = System.nanoTime(), matches = 0;
        int capacity = Math.min(k, dataset.count());
        PriorityQueue<Neighbor> heap = new PriorityQueue<>(Math.max(1, capacity), SearchResult.ORDER.reversed());
        for (int id = 0; id < dataset.count(); id++) {
            if (!filter.get(id)) continue;
            matches++;
            double distance = dataset.squaredL2(query, id);
            if (heap.size() < capacity) heap.add(new Neighbor(id, distance));
            else {
                Neighbor worst = heap.peek();
                if (distance < worst.squaredL2() || (distance == worst.squaredL2() && id < worst.vectorId())) {
                    heap.remove(); heap.add(new Neighbor(id, distance));
                }
            }
        }
        List<Neighbor> result = new ArrayList<>(heap); result.sort(SearchResult.ORDER);
        return new SearchResult(result, new SearchStats(matches, matches, System.nanoTime() - start));
    }
}
