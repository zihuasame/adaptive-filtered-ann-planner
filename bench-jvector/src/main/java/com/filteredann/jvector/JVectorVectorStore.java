package com.filteredann.jvector;

import com.filteredann.dataset.Dataset;
import io.github.jbellis.jvector.graph.ListRandomAccessVectorValues;
import io.github.jbellis.jvector.graph.RandomAccessVectorValues;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;
import java.util.*;

/** Read-only rows. Node ordinal is always the original Dataset vector ID. */
public final class JVectorVectorStore {
    private final List<VectorFloat<?>> vectors;
    private final RandomAccessVectorValues ravv;
    private final int dimensions;
    public JVectorVectorStore(Dataset dataset) {
        Objects.requireNonNull(dataset);
        dimensions = dataset.dimensions();
        VectorTypeSupport support = VectorizationProvider.getInstance().getVectorTypeSupport();
        ArrayList<VectorFloat<?>> rows = new ArrayList<>(dataset.count());
        for (int id = 0; id < dataset.count(); id++) rows.add(support.createFloatVector(dataset.vector(id)));
        vectors = Collections.unmodifiableList(rows);
        ravv = new ListRandomAccessVectorValues(vectors, dimensions);
    }
    public RandomAccessVectorValues values() { return ravv; }
    public int count() { return vectors.size(); }
    public int dimensions() { return dimensions; }
    public long floatPayloadBytes() { return (long) count() * dimensions * Float.BYTES; }
    /** Library estimates of vector objects/arrays, excludes list bookkeeping. */
    public long libraryVectorBytes() { return vectors.stream().mapToLong(VectorFloat::ramBytesUsed).sum(); }
    public void verifyMapping(Dataset source, int samples, long seed) {
        if (source.count() != count() || source.dimensions() != dimensions) throw new IllegalArgumentException("Source/store shape differs");
        if (samples < 0 || samples > count()) throw new IllegalArgumentException("Invalid mapping sample size");
        Random random = new Random(seed);
        Set<Integer> ids = new LinkedHashSet<>();
        while (ids.size() < samples) ids.add(random.nextInt(count()));
        for (int id : ids) {
            float[] expected = source.vector(id); VectorFloat<?> actual = ravv.getVector(id);
            for (int d = 0; d < dimensions; d++) {
                if (Float.floatToIntBits(expected[d]) != Float.floatToIntBits(actual.get(d))) {
                    throw new IllegalStateException("Vector mapping mismatch at ID " + id + " dimension " + d);
                }
            }
        }
    }
}
