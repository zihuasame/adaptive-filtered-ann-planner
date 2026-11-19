package com.filteredann.filter;

import com.filteredann.metadata.MetadataStore;
import java.util.BitSet;

public final class FilterBitmap {
    private final int length;
    private final BitSet bits;
    private FilterBitmap(int length, BitSet bits) { this.length = length; this.bits = bits; }
    public static FilterBitmap of(int length, int... ids) {
        if (length < 0) throw new IllegalArgumentException("Negative bitmap length");
        BitSet bits = new BitSet(length);
        for (int id : ids) {
            if (id < 0 || id >= length) throw new IndexOutOfBoundsException(id);
            bits.set(id);
        }
        return new FilterBitmap(length, bits);
    }
    public static FilterBitmap all(int length) {
        FilterBitmap bitmap = of(length); bitmap.bits.set(0, length); return bitmap;
    }
    public static FilterBitmap build(MetadataStore metadata, FilterPredicate predicate) {
        BitSet bits = new BitSet(metadata.count());
        for (int id = 0; id < metadata.count(); id++) if (predicate.matches(metadata, id)) bits.set(id);
        return new FilterBitmap(metadata.count(), bits);
    }
    public boolean get(int id) {
        if (id < 0 || id >= length) throw new IndexOutOfBoundsException(id);
        return bits.get(id);
    }
    public int cardinality() { return bits.cardinality(); }
    public int length() { return length; }
    public FilterBitmap and(FilterBitmap other) {
        if (length != other.length) throw new IllegalArgumentException("Bitmap length mismatch");
        BitSet result = (BitSet) bits.clone(); result.and(other.bits);
        return new FilterBitmap(length, result);
    }
}
