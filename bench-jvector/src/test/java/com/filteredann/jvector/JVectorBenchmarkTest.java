package com.filteredann.jvector;

import com.filteredann.dataset.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JVectorBenchmarkTest {
    @TempDir Path temp;
    @Test void queryPermutationUsesAllQueriesWithoutFiltering() {
        int[] order=JVectorBenchmarkRunner.queryOrder(10000,20261113L);
        assertArrayEquals(order,JVectorBenchmarkRunner.queryOrder(10000,20261113L));
        int[] sorted=order.clone(); Arrays.sort(sorted);
        assertArrayEquals(java.util.stream.IntStream.range(0,10000).toArray(),sorted);
        assertThrows(java.nio.file.NoSuchFileException.class,()->new SiftDatasetReader().readVectors(temp.resolve("missing.fvecs"),1000000,128));
    }
    @Test void exporterWritesOutsideMeasuredBatchWithRoundAndThreadDimensions() throws Exception {
        float[] values=new float[10]; for(int i=0;i<10;i++) values[i]=i;
        Dataset base=new Dataset(values,1), query=new Dataset(new float[]{0},1);
        int[] ids=java.util.stream.IntStream.range(0,10).toArray(); float[] scores=new float[10];
        for(int i=0;i<10;i++) scores[i]=1f/(1f+i*i);
        var outcome=new JVectorSearchIndex.Outcome(ids,scores,20,10,8,0,1,2,30,4,37);
        var batch=new JVectorBenchmarkRunner.Batch(new JVectorSearchIndex.Outcome[]{outcome},100);
        try(var exporter=new JVectorResultExporter(temp)) {
            var summary=exporter.exportBatch(base,query,new int[][]{ids},new int[]{0},10,2,8,batch,true);
            assertEquals(1.0,summary.get("recall")); assertEquals(2,summary.get("repeatId")); assertEquals(8,summary.get("threadCount"));
        }
        assertEquals(11,Files.readAllLines(temp.resolve("jvector-query-results.csv")).size());
        assertTrue(Files.readString(temp.resolve("jvector-query-latency.csv")).contains("0,10,10,2,8,30,10,20,10,8,0,1,2,4,37"));
    }
}
