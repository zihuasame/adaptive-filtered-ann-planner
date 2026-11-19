// SPDX-License-Identifier: Apache-2.0
package com.filteredann.planner;

import com.filteredann.filter.FilterBitmap;
import com.filteredann.metadata.*;
import com.filteredann.planner.api.*;
import com.filteredann.planner.catalog.PerformanceCatalog;
import com.filteredann.planner.core.Planner;
import com.filteredann.planner.engine.*;
import com.filteredann.planner.filter.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

class PlannerQueryTest {
    static RowsVectorStore vectors;static ProductGraphFactory.BuiltIndex graph;static FilterEngine filters;
    static PerformanceCatalog catalog;static Planner.Context context;
    static double distance(int id){return 5.0*id*id+(id%3)*(id%3);}
    @BeforeAll static void setup()throws Exception {
        float[][] rows=new float[128][4];int[] ranks=new int[128],categories=new int[128],regions=new int[128];
        for(int id=0;id<128;id++){rows[id]=new float[]{id,2*id,id%3,0};ranks[id]=id;regions[id]=id%8;}
        vectors=RowsVectorStore.of(rows);filters=new FilterEngine(new MetadataStore(ranks,categories,regions),ranks,128,4);
        graph=new ProductGraphFactory().build(vectors,ProductGraphFactory.BuildParameters.baseline(),null);
        catalog=new PerformanceCatalog(Path.of(System.getProperty("planner.catalog")));
        context=new Planner.Context("unit-fixture-not-measured",128,4,false,"small-functional-test");
    }
    @AfterAll static void close()throws Exception{graph.close();}
    QueryApi session(){return new QueryApi(vectors,graph.graph(),filters,catalog,context);}
    @Test void fourActualStrategiesPreservePostFilterAndOversampleSemantics()throws Exception {
        try(var engine=new FourStrategyEngine(vectors,vectors,graph.graph())) {
            var rare=FilterBitmap.of(128,127);float[] query={0,0,0,0};
            assertEquals(0,engine.search(query,rare,FourStrategyEngine.Strategy.P,128,128).ids().length);
            for(var s:List.of(FourStrategyEngine.Strategy.E,FourStrategyEngine.Strategy.O,FourStrategyEngine.Strategy.N))assertArrayEquals(new int[]{127},engine.search(query,rare,s,128,128).ids());
            var p=engine.search(query,FilterBitmap.all(128),FourStrategyEngine.Strategy.P,10,10);
            var o=engine.search(query,FilterBitmap.all(128),FourStrategyEngine.Strategy.O,10,10);
            assertArrayEquals(p.ids(),o.ids());assertEquals(p.visited(),o.visited());assertEquals(p.expanded(),o.expanded());
        }
    }
    @Test void unifiedApiReturnsExactFilteredIdsAndOptionalRecall()throws Exception {
        var spec=FilterSpec.equalTo(FilterSpec.Field.REGION,7);float[] query={0,0,0,0};
        var prepared=filters.acquire(spec,false);int[] ids=new int[10];double[] ds=new double[10];for(int i=0;i<10;i++){ids[i]=7+8*i;ds[i]=distance(ids[i]);}
        var gt=new GroundTruth(context.datasetId(),GroundTruth.queryHash(query),prepared.bitmapSha256(),ids,ds,"independent-analytic-fixture");
        try(var api=session()) {
            var response=api.query(new QueryRequest(query,spec,.95),gt);
            assertEquals(Arrays.stream(ids).boxed().toList(),response.resultVectorIds());assertEquals(1.0,response.actualRecall());assertTrue(response.actualTargetMet());assertTrue(response.decision().safeFallback());
            var unbound=api.query(new QueryRequest(query,spec,.95));assertNull(unbound.actualRecall());assertNull(unbound.actualTargetMet());
        }
    }
    @Test void rejectsIncorrectGroundTruthBindingAndInput()throws Exception {
        var spec=FilterSpec.equalTo(FilterSpec.Field.REGION,7);
        try(var api=session()) {
            var bad=new GroundTruth("other",GroundTruth.queryHash(new float[4]),"wrong",new int[0],new double[0],"bad");
            assertThrows(IllegalArgumentException.class,()->api.query(new QueryRequest(new float[4],spec,.95),bad));
            assertThrows(IllegalArgumentException.class,()->api.query(new QueryRequest(new float[3],spec,.95)));
        }
        assertThrows(IllegalArgumentException.class,()->new QueryRequest(new float[]{Float.NaN},spec,.95));
        assertThrows(IllegalArgumentException.class,()->new QueryRequest(new float[4],spec,.95,9,false));
    }
    @Test void emptyFiltersShortCircuitAndCacheKeysAreCanonical()throws Exception {
        var spec=FilterSpec.equalTo(FilterSpec.Field.REGION,99);
        try(var api=session()){var r=api.query(new QueryRequest(new float[4],spec,.95));assertTrue(r.resultVectorIds().isEmpty());assertNull(r.actualRecall());}
        var a=FilterSpec.equalTo(FilterSpec.Field.CATEGORY,0).and(FilterSpec.lessThan(FilterSpec.Field.RANDOM_RANK,100));
        var b=FilterSpec.lessThan(FilterSpec.Field.RANDOM_RANK,100).and(FilterSpec.equalTo(FilterSpec.Field.CATEGORY,0));
        filters.clear();assertEquals(FilterEngine.CacheState.COLD,filters.acquire(a,false).cacheState());assertEquals(FilterEngine.CacheState.HOT,filters.acquire(b,false).cacheState());assertEquals(FilterEngine.CacheState.COLD,filters.acquire(b,true).cacheState());
    }
    @Test void independentSessionsAreSafeAndSharedSessionIsRejected()throws Exception {
        try(var api=session();var pool=Executors.newFixedThreadPool(2)) {
            var spec=FilterSpec.equalTo(FilterSpec.Field.CATEGORY,0);
            var wrong=pool.submit(()->api.query(new QueryRequest(new float[4],spec,.95)));
            assertInstanceOf(IllegalStateException.class,assertThrows(ExecutionException.class,wrong::get).getCause());
            Callable<List<Integer>> work=()->{try(var own=session()){return own.query(new QueryRequest(new float[4],spec,.95)).resultVectorIds();}};
            var a=pool.submit(work);var b=pool.submit(work);assertEquals(a.get(),b.get());
        }
    }
}
