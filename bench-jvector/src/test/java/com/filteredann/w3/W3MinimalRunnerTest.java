package com.filteredann.w3;

import com.filteredann.dataset.Dataset;
import com.filteredann.jvector.*;
import com.filteredann.metadata.MetadataGenerator;
import com.filteredann.filter.FilterBitmap;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class W3MinimalRunnerTest {
    @Test void isolatedBatchAndLabelsRoundTrip(@TempDir Path dir) throws Exception {
        float[] values=new float[64]; for(int i=0;i<values.length;i++) values[i]=i;
        var base=new Dataset(values,1); var queries=new Dataset(new float[]{63,62},1);
        var labels=W3Workload.labels(base); W3Workload.writeLabels(dir.resolve("labels.bin"),labels);
        var loaded=W3Workload.readLabels(dir.resolve("labels.bin"),64); assertArrayEquals(labels.rank(),loaded.rank()); assertArrayEquals(labels.anchors(),loaded.anchors());
        assertThrows(java.io.IOException.class,()->W3Workload.readLabels(dir.resolve("labels.bin"),63));
        var in=new W3MinimalRunner.Inputs(base,queries,new MetadataGenerator().generate(64,20261113),new int[]{400,401});
        var graph=new JVectorIndexFactory.BuiltIndex(new W3Phase0Probe.ChainGraph(64,1,-1),Map.of(),Map.of());
        var store=new JVectorVectorStore(base);
        var cell=new W3MinimalRunner.Cell("test","reuse","all",W3Strategies.Strategy.N,40,0,8);
        var result=W3MinimalRunner.batch(in,store,graph,cell,()->FilterBitmap.all(64),20);
        assertEquals(2,result.outcomes().length); assertTrue(result.wallNanos()>0);
        assertEquals(10,result.outcomes()[0].ids().length); assertEquals(63,result.outcomes()[0].ids()[0]);
        assertEquals(62,result.outcomes()[1].ids()[0]);
    }
}
