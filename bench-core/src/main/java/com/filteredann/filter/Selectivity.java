package com.filteredann.filter;

public final class Selectivity {
    private Selectivity() { }
    public static double of(FilterBitmap bitmap) {
        return bitmap.length() == 0 ? 0.0 : (double) bitmap.cardinality() / bitmap.length();
    }
}
