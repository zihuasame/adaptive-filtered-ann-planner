package com.filteredann;

import com.filteredann.dataset.*;
import com.filteredann.metadata.*;
import com.filteredann.filter.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.*;
import java.nio.file.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class DataFilterTest {
    @TempDir Path temp;
    @Test void readsVectorsAndRejectsBadFiles() throws Exception {
        Path file = temp.resolve("tiny.fvecs");
        ByteBuffer b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(2).putFloat(1).putFloat(2).putInt(2).putFloat(3).putFloat(4);
        Files.write(file, b.array());
        SiftDatasetReader reader = new SiftDatasetReader();
        Dataset data = reader.readVectors(file, 2, 2);
        assertArrayEquals(new float[]{3, 4}, data.vector(1));
        assertEquals(8, data.squaredL2(new float[]{1, 2}, 1));
        assertThrows(IOException.class, () -> reader.readVectors(file, 3, 2));
        b.putInt(12, 3); Files.write(file, b.array());
        assertThrows(IOException.class, () -> reader.readVectors(file, 2, 2));
        b.putInt(12, 2).putFloat(4, Float.NaN); Files.write(file, b.array());
        assertThrows(IOException.class, () -> reader.readVectors(file, 2, 2));
        Files.write(file, new byte[]{1,2,3});
        assertThrows(IOException.class, () -> reader.readVectors(file, 1, 2));
    }
    @Test void officialGroundTruthValidatesEveryHeaderAndIds() throws Exception {
        Path file = temp.resolve("gt.ivecs");
        ByteBuffer b = ByteBuffer.allocate(808).order(ByteOrder.LITTLE_ENDIAN);
        for (int q = 0; q < 2; q++) { b.putInt(100); for (int id = 0; id < 100; id++) b.putInt(id); }
        Files.write(file,b.array());
        assertEquals(99,new SiftDatasetReader().readGroundTruth(file,2,100)[1][99]);
        b.putInt(404,99); Files.write(file,b.array());
        assertThrows(IOException.class,()->new SiftDatasetReader().readGroundTruth(file,2,100));
        b.putInt(404,100).putInt(408,999); Files.write(file,b.array());
        assertThrows(IOException.class,()->new SiftDatasetReader().readGroundTruth(file,2,100));
        b.putInt(408,1); Files.write(file,b.array());
        assertThrows(IOException.class,()->new SiftDatasetReader().readGroundTruth(file,2,100));
    }
    @Test void metadataReproducibleLittleEndianAndReloadable() throws Exception {
        MetadataGenerator gen = new MetadataGenerator();
        MetadataStore a = gen.generate(1000,20261113L), b = gen.generate(1000,20261113L);
        Path first=temp.resolve("a.bin"), second=temp.resolve("b.bin");
        gen.write(first,a,20261113L); gen.write(second,b,20261113L);
        assertArrayEquals(Files.readAllBytes(first),Files.readAllBytes(second));
        assertEquals(com.filteredann.util.Artifacts.sha256(first),com.filteredann.util.Artifacts.sha256(second));
        assertArrayEquals(a.filterRanks(),gen.read(first,20261113L).filterRanks());
        ByteBuffer header=ByteBuffer.wrap(Files.readAllBytes(first)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(12032,header.capacity()); assertEquals(1,header.getInt(8));
        assertEquals(1000,header.getInt(12)); assertEquals(20261113L,header.getLong(16));
        assertThrows(IOException.class,()->gen.read(first,1));
        assertThrows(IllegalArgumentException.class,()->new MetadataStore(new int[]{0,0},new int[2],new int[2]));
        header.putInt(32,-1); Files.write(second,header.array());
        assertThrows(IOException.class,()->gen.read(second,20261113L));
    }
    @Test void bitmapPreservesLengthAndMatchesAndPredicates() {
        MetadataStore m=new MetadataGenerator().generate(1000,20261113L);
        for(int threshold:new int[]{0,1,10,100,500,1000}) {
            FilterBitmap bitmap=FilterBitmap.build(m,FilterPredicate.rankBelow(threshold));
            assertEquals(threshold,bitmap.cardinality()); assertEquals(1000,bitmap.length());
            assertEquals(threshold/1000.0,Selectivity.of(bitmap));
        }
        FilterBitmap categories=FilterBitmap.build(m,FilterPredicate.category(3));
        FilterBitmap regions=FilterBitmap.build(m,FilterPredicate.region(2));
        FilterBitmap combined=FilterBitmap.build(m,FilterPredicate.category(3).and(FilterPredicate.region(2)));
        FilterBitmap intersection=categories.and(regions);
        for(int id=0;id<1000;id++) {
            assertEquals(m.categories()[id]==3 && m.regions()[id]==2,combined.get(id));
            assertEquals(combined.get(id),intersection.get(id));
        }
        assertThrows(IllegalArgumentException.class,()->categories.and(FilterBitmap.all(999)));
        assertThrows(IndexOutOfBoundsException.class,()->categories.get(1000));
        assertThrows(IndexOutOfBoundsException.class,()->FilterBitmap.of(2,-1));
        assertEquals(0,Selectivity.of(FilterBitmap.of(0)));
    }
}
