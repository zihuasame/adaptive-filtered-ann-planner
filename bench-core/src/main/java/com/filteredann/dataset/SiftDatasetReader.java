package com.filteredann.dataset;

import java.io.*;
import java.nio.*;
import java.nio.file.*;

public final class SiftDatasetReader {
    public Dataset readVectors(Path path, int count, int dimensions) throws IOException {
        validateSize(path, count, dimensions);
        float[] values = new float[Math.multiplyExact(count, dimensions)];
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path), 1 << 20)) {
            byte[] row = new byte[(dimensions + 1) * 4];
            ByteBuffer buffer = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN);
            for (int id = 0; id < count; id++) {
                readRow(in, row, buffer, dimensions, id);
                for (int d = 0; d < dimensions; d++) values[id * dimensions + d] = buffer.getFloat();
            }
        }
        try { return new Dataset(values, dimensions); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid fvecs: " + path, e); }
    }

    public int[][] readGroundTruth(Path path, int count, int baseCount) throws IOException {
        validateSize(path, count, 100);
        int[][] ids = new int[count][100];
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path), 1 << 20)) {
            byte[] row = new byte[404];
            ByteBuffer buffer = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN);
            for (int id = 0; id < count; id++) {
                readRow(in, row, buffer, 100, id);
                java.util.HashSet<Integer> seen = new java.util.HashSet<>();
                for (int rank = 0; rank < 100; rank++) {
                    int neighbor = buffer.getInt();
                    if (neighbor < 0 || neighbor >= baseCount || !seen.add(neighbor)) {
                        throw new IOException("Invalid/duplicate official neighbor at query " + id);
                    }
                    ids[id][rank] = neighbor;
                }
            }
        }
        return ids;
    }

    private void validateSize(Path path, int count, int dimensions) throws IOException {
        if (count < 0 || dimensions <= 0) throw new IllegalArgumentException("Invalid expected shape");
        long expected = Math.multiplyExact((long) count, 4L * (dimensions + 1));
        if (Files.size(path) != expected) throw new IOException("Wrong file size: " + path + ", expected " + expected);
    }

    private void readRow(InputStream in, byte[] row, ByteBuffer buffer, int dimensions, int id) throws IOException {
        if (in.readNBytes(row, 0, row.length) != row.length) throw new EOFException("Truncated row " + id);
        buffer.clear();
        if (buffer.getInt() != dimensions) throw new IOException("Dimension mismatch at row " + id);
    }
}
