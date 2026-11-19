package com.filteredann.metadata;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Random;

public final class MetadataGenerator {
    public static final String ALGORITHM = "java.util.Random (48-bit LCG)";
    public static final String VERSION = "random-v1: Fisher-Yates descending; then category/region interleaved";

    public MetadataStore generate(int count, long seed) {
        if (count < 0) throw new IllegalArgumentException("Negative count");
        Random random = new Random(seed);
        int[] ranks = new int[count], categories = new int[count], regions = new int[count];
        for (int id = 0; id < count; id++) ranks[id] = id;
        for (int i = count - 1; i > 0; i--) {
            int j = random.nextInt(i + 1), swap = ranks[i];
            ranks[i] = ranks[j]; ranks[j] = swap;
        }
        for (int id = 0; id < count; id++) {
            categories[id] = random.nextInt(16);
            regions[id] = random.nextInt(8);
        }
        return new MetadataStore(ranks, categories, regions);
    }

    public void write(Path path, MetadataStore metadata, long seed) throws IOException {
        Files.createDirectories(path.getParent());
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            out.write("FANNMETA".getBytes(StandardCharsets.US_ASCII));
            writeInt(out, 1); writeInt(out, metadata.count());
            out.writeLong(Long.reverseBytes(seed)); writeInt(out, 16); writeInt(out, 8);
            for (int id = 0; id < metadata.count(); id++) {
                writeInt(out, metadata.filterRanks()[id]); writeInt(out, metadata.categories()[id]); writeInt(out, metadata.regions()[id]);
            }
        }
    }

    public MetadataStore read(Path path, long seed) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (!new String(in.readNBytes(8), StandardCharsets.US_ASCII).equals("FANNMETA") || readInt(in) != 1) {
                throw new IOException("Invalid metadata header");
            }
            int count = readInt(in);
            if (count < 0 || Files.size(path) != 32L + count * 12L || Long.reverseBytes(in.readLong()) != seed
                    || readInt(in) != 16 || readInt(in) != 8) throw new IOException("Invalid metadata shape/seed");
            int[] ranks = new int[count], categories = new int[count], regions = new int[count];
            for (int id = 0; id < count; id++) {
                ranks[id] = readInt(in); categories[id] = readInt(in); regions[id] = readInt(in);
            }
            try { return new MetadataStore(ranks, categories, regions); }
            catch (IllegalArgumentException e) { throw new IOException("Invalid metadata records", e); }
        }
    }
    private void writeInt(DataOutputStream out, int value) throws IOException { out.writeInt(Integer.reverseBytes(value)); }
    private int readInt(DataInputStream in) throws IOException { return Integer.reverseBytes(in.readInt()); }
}
