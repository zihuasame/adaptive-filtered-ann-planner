package com.filteredann.w3;

import com.filteredann.dataset.Dataset;
import com.filteredann.filter.*;
import com.filteredann.evaluation.GroundTruthGenerator;
import com.filteredann.metadata.MetadataGenerator;
import com.filteredann.index.SearchResult;
import com.filteredann.index.SearchResult.Neighbor;
import com.filteredann.jvector.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class W3CorrectnessTest {
    @Test void exactMatchesIndependentFullSortAndTies() {
        Random random=new Random(18); float[] rows=new float[64*3]; for(int i=0;i<rows.length;i++) rows[i]=random.nextInt(4);
        Dataset base=new Dataset(rows,3); var gt=new GroundTruthGenerator(base); float[] query={1,2,3};
        for(int n:new int[]{0,1,9,10,11,64}) {
            int[] ids=java.util.stream.IntStream.range(0,n).toArray(); var filter=FilterBitmap.of(64,ids);
            List<Neighbor> expected=Arrays.stream(ids).mapToObj(id->new Neighbor(id,base.squaredL2(query,id))).sorted(SearchResult.ORDER).limit(10).toList();
            assertEquals(expected,gt.filtered(query,filter,10).neighbors());
            var quality=W3Validation.validate(base,query,filter,expected.stream().mapToInt(Neighbor::vectorId).toArray(),expected);
            if(n>0) assertEquals(1,quality.strict()); else assertTrue(Double.isNaN(quality.strict()));
        }
        Dataset identical=new Dataset(new float[32],1); var truth=new GroundTruthGenerator(identical).global(new float[]{0},10).neighbors();
        int[] alternative=java.util.stream.IntStream.range(10,20).toArray();
        var q=W3Validation.validate(identical,new float[]{0},FilterBitmap.all(32),alternative,truth);
        assertEquals(0,q.strict()); assertEquals(1,q.tieAware());
        assertThrows(IllegalStateException.class,()->W3Validation.validate(identical,new float[]{0},FilterBitmap.all(32),new int[]{1,1},truth));
        assertThrows(IllegalStateException.class,()->W3Validation.validate(identical,new float[]{0},FilterBitmap.all(32),new int[]{32},truth));
    }
    @Test void labelsDeterministicAndIntersectionsExact() {
        float[] values=new float[64*2]; for(int i=0;i<values.length;i++) values[i]=i%11;
        Dataset base=new Dataset(values,2); var a=W3Workload.labels(base); var b=W3Workload.labels(base);
        assertArrayEquals(a.rank(),b.rank()); assertArrayEquals(a.anchors(),b.anchors());
        assertEquals(64,Arrays.stream(a.rank()).distinct().count());
        var metadata=new MetadataGenerator().generate(64,20261113);
        var filters=W3Workload.filters(metadata,a); assertEquals(19,filters.size());
        for(var filter:filters) assertEquals(W3Workload.bitmapHash(filter.bitmap()),W3Workload.bitmapHash(filter.build(metadata)));
        assertEquals(0,FilterBitmap.of(64,1).and(FilterBitmap.of(64,2)).cardinality());
    }
    @Test void strategiesAnchorsEmptyAndEightIndependentWorkers() throws Exception {
        float[] rows=new float[64]; for(int i=0;i<64;i++) rows[i]=i;
        Dataset base=new Dataset(rows,1); var store=new JVectorVectorStore(base); var graph=new W3Phase0Probe.ChainGraph(64,1,-1);
        var gt=new GroundTruthGenerator(base);
        try(var worker=new W3Strategies(base,store,graph)) {
            for(int n:new int[]{0,1,9,10,11}) {
                var filter=FilterBitmap.of(64,java.util.stream.IntStream.range(64-n,64).toArray());
                for(var strategy:W3Strategies.Strategy.values()) {
                    var outcome=worker.search(new float[]{63},filter,strategy,80,10);
                    W3Validation.validate(base,new float[]{63},filter,outcome.ids(),gt.filtered(new float[]{63},filter,10).neighbors());
                    if(n==0) { assertEquals(0,outcome.visited()); assertEquals(0,outcome.coreSearch()); }
                }
                assertArrayEquals(worker.search(new float[]{63},filter,W3Strategies.Strategy.P,80,10).ids(),worker.search(new float[]{63},filter,W3Strategies.Strategy.O,80,10).ids());
            }
            assertArrayEquals(worker.search(new float[]{63},FilterBitmap.all(64),W3Strategies.Strategy.P,40,10).ids(),worker.search(new float[]{63},FilterBitmap.all(64),W3Strategies.Strategy.N,40,10).ids());
            assertThrows(IllegalArgumentException.class,()->worker.search(new float[]{63},FilterBitmap.all(63),W3Strategies.Strategy.N,40,10));
            assertThrows(IllegalArgumentException.class,()->worker.search(new float[]{63},FilterBitmap.all(64),W3Strategies.Strategy.P,9,10));
            try(var pool=Executors.newSingleThreadExecutor()) {
                assertThrows(ExecutionException.class,()->pool.submit(()->worker.search(new float[]{63},FilterBitmap.all(64),W3Strategies.Strategy.N,40,10)).get());
            }
        }
        try(var pool=Executors.newFixedThreadPool(8)) {
            List<Callable<int[]>> tasks=new ArrayList<>();
            for(int t=0;t<8;t++) tasks.add(()->{ try(var worker=new W3Strategies(base,store,graph)) {
                int[] ids=null; for(int r=0;r<10;r++) { int[] actual=worker.search(new float[]{63},FilterBitmap.all(64),W3Strategies.Strategy.N,40,10).ids(); if(ids!=null) assertArrayEquals(ids,actual); ids=actual; } return ids;
            }});
            for(var future:pool.invokeAll(tasks)) assertEquals(10,future.get().length);
        }
    }
    @Test void nearestRank() {
        assertEquals(99,W3Validation.quantile(java.util.stream.LongStream.rangeClosed(1,100).toArray(),.99));
        assertEquals(3,W3Validation.quantile(new long[]{3,1,2},.99));
    }
    @Test void streamedGTRefusesOverwriteAndContainsExpectedRows(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        Dataset base=new Dataset(new float[32],1),queries=new Dataset(new float[]{0,1},1);
        var labels=W3Workload.labels(base); var metadata=new MetadataGenerator().generate(32,20261113);
        var filter=W3Workload.filters(metadata,labels).getFirst();
        var manifest=W3GroundTruthExport.write(dir,base,queries,filter);
        assertEquals(2,manifest.get("groups")); assertEquals(20L,manifest.get("rows"));
        assertEquals(21,java.nio.file.Files.readAllLines(dir.resolve(filter.id()+".csv")).size());
        assertThrows(java.nio.file.FileAlreadyExistsException.class,()->W3GroundTruthExport.write(dir,base,queries,filter));
    }
}
