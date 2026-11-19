package com.filteredann.index;

import com.filteredann.filter.FilterBitmap;

public interface SearchIndex {
    String name();
    SearchResult search(float[] query, FilterBitmap filter, int k);
}
