package com.filteredann;

import com.filteredann.evaluation.RecallHarness;
import com.filteredann.util.Artifacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HarnessTest {
    @TempDir Path temp;
    @Test void sampleIsDeterministicDistinctAndSorted() {
        var sample=RecallHarness.sampleQueries(10000,50,20261113L);
        assertEquals(sample,RecallHarness.sampleQueries(10000,50,20261113L));
        assertEquals(50,new HashSet<>(sample).size());
        assertEquals(sample,sample.stream().sorted().toList());
        assertTrue(sample.containsAll(RecallHarness.sampleQueries(10000,10,20261113L)));
        assertThrows(IllegalArgumentException.class,()->RecallHarness.sampleQueries(3,4,1));
    }
    @Test void configurationRejectsSilentDownsizing() {
        assertThrows(IllegalArgumentException.class,()->new RecallHarness.Config(1000,10000,128,10,50,20261113L,List.of(500)));
        assertThrows(IllegalArgumentException.class,()->new RecallHarness.Config(1000000,10000,128,10,10000,20261113L,List.of(500)));
        assertThrows(IllegalArgumentException.class,()->new RecallHarness.Config(1000000,10000,128,10,50,20261113L,List.of(500,500)));
    }
    @Test void sha256UsesFileContents() throws Exception {
        Path file=temp.resolve("abc"); Files.writeString(file,"abc");
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",Artifacts.sha256(file));
        Files.writeString(file,"abcd");
        assertNotEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",Artifacts.sha256(file));
    }
    @Test void checksumMismatchStopsBeforeDatasetLoading() throws Exception {
        Files.createDirectories(temp.resolve("data/raw")); Files.createDirectories(temp.resolve("config"));
        Files.writeString(temp.resolve("data/raw/sift_base.fvecs"),"bad data");
        Files.writeString(temp.resolve("config/checksums.json"),"{\"sift_base.fvecs\":\"wrong\"}");
        var config=new RecallHarness.Config(1000000,10000,128,10,10,20261113L,List.of(1000));
        var error=assertThrows(java.io.IOException.class,()->new RecallHarness().run(temp,config,"w1"));
        assertTrue(error.getMessage().contains("SHA-256 mismatch"));
        assertFalse(Files.exists(temp.resolve("data/processed/metadata/sift1m-random-v1.bin")));
        assertTrue(Files.readString(temp.resolve("results/w1/generated/checksum-report.json")).contains("false"));
    }
}
