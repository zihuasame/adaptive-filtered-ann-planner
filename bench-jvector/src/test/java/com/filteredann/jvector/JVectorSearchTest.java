package com.filteredann.jvector;

import com.filteredann.dataset.Dataset;
import com.filteredann.filter.FilterBitmap;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class JVectorSearchTest {
    @Test void searchScoresIdsAndCoreInterfaceAreValid() throws Exception {
        Dataset base=JVectorIndexTest.randomData(300,128); var store=new JVectorVectorStore(base);
        try(var built=new JVectorIndexFactory().build(store,JVectorIndexFactory.BuildParameters.baseline(),null);
            var searcher=new JVectorSearchIndex(base,store,built.graph(),300)) {
            var result=searcher.search(base,10,new JVectorSearchOptions(10,300));
            JVectorSearchIndex.validateOutcome(result,300,10);
            assertEquals(10,result.ids()[0]); assertEquals(1f,result.scores()[0]); assertEquals(0,result.rerankedCount());
            assertTrue(result.endToEndNanos()>=result.graphSearchNanos());
            assertEquals(10,searcher.search(base.vector(10),FilterBitmap.all(300),10).neighbors().getFirst().vectorId());
            assertThrows(UnsupportedOperationException.class,()->searcher.search(base.vector(10),FilterBitmap.of(300,10),10));
            assertThrows(IllegalArgumentException.class,()->new JVectorSearchOptions(10,9));
            assertThrows(IllegalArgumentException.class,()->new JVectorSearchOptions(0,10));
        }
    }
    @Test void concurrentWorkersOwnIndependentSearchersAndSharingIsRejected() throws Exception {
        Dataset base=JVectorIndexTest.randomData(500,16); var store=new JVectorVectorStore(base);
        try(var built=new JVectorIndexFactory().build(store,JVectorIndexFactory.BuildParameters.baseline(),null);
            var shared=new JVectorSearchIndex(base,store,built.graph(),500);
            var executor=Executors.newFixedThreadPool(8)) {
            List<Future<Integer>> tasks=new ArrayList<>();
            Set<JVectorSearchIndex> instances=ConcurrentHashMap.newKeySet();
            for(int worker=0;worker<8;worker++) {
                final int id=worker;
                tasks.add(executor.submit(()->{
                    assertThrows(IllegalStateException.class,()->shared.search(base,id,new JVectorSearchOptions(10,500)));
                    try(var own=new JVectorSearchIndex(base,store,built.graph(),500)) {
                        instances.add(own);
                        for(int j=0;j<10;j++) {
                            var result=own.search(base,id,new JVectorSearchOptions(10,500));
                            JVectorSearchIndex.validateOutcome(result,500,10); assertEquals(id,result.ids()[0]);
                        }
                    }
                    return id;
                }));
            }
            for(var future:tasks) future.get(); assertEquals(8,instances.size());
        }
    }
    @Test void emptyGraphReturnsEmptyAndClosedSearcherRejectsUse() throws Exception {
        Dataset base=new Dataset(new float[0],2); var store=new JVectorVectorStore(base);
        try(var built=new JVectorIndexFactory().build(store,JVectorIndexFactory.BuildParameters.baseline(),null)) {
            var searcher=new JVectorSearchIndex(base,store,built.graph(),10);
            assertEquals(0,searcher.search(new float[]{0,0},new JVectorSearchOptions(10,10)).ids().length);
            searcher.close();
            assertThrows(IllegalStateException.class,()->searcher.search(new float[]{0,0},new JVectorSearchOptions(10,10)));
        }
    }
}
