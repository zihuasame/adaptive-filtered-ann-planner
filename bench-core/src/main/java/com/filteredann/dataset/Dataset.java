package com.filteredann.dataset;

public final class Dataset {
    private final float[] values;
    private final int dimensions;

    public Dataset(float[] values, int dimensions) {
        if (dimensions <= 0 || values.length % dimensions != 0) {
            throw new IllegalArgumentException("Invalid vector shape");
        }
        for (float value : values) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite vector value");
        }
        this.values = values;
        this.dimensions = dimensions;
    }

    public int count() { return values.length / dimensions; }
    public int dimensions() { return dimensions; }
    public float[] vector(int id) {
        if (id < 0 || id >= count()) throw new IndexOutOfBoundsException(id);
        return java.util.Arrays.copyOfRange(values, id * dimensions, (id + 1) * dimensions);
    }

    public double squaredL2(float[] query, int id) {
        int offset = id * dimensions;
        double sum = 0;
        for (int d = 0; d < dimensions; d++) {
            double delta = (double) query[d] - values[offset + d];
            sum += delta * delta;
        }
        return sum;
    }
}
