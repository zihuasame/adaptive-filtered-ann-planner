package com.filteredann.jvector;

import com.filteredann.dataset.Dataset;
import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class JVectorIndexTest {
    static Dataset randomData(int n,int dims) {
        Random random=new Random(42); float[] values=new float[n*dims];
        for(int i=0;i<values.length;i++) values[i]=random.nextInt(256);
        return new Dataset(values,dims);
    }
    @Test void constructsSmallHierarchicalGraphAndAccountsForMemory() throws Exception {
        var store=new JVectorVectorStore(randomData(1000,128));
        try(var built=new JVectorIndexFactory().build(store,JVectorIndexFactory.BuildParameters.baseline(),null)) {
            assertEquals(1000,built.graph().size(0)); assertEquals(128,built.graph().getDimension());
            assertTrue(built.graph().isHierarchical()); assertTrue(built.graph().getMaxLevel()>0);
            for(int id=0;id<1000;id++) assertTrue(built.graph().containsNode(id));
            assertTrue(((Number)built.performance().get("graphLibraryRamBytes")).longValue()>0);
        }
    }
    @Test void percentileUsesNearestRankAndQpsUsesElapsedWallTime() {
        long[] values=new long[100]; for(int i=0;i<100;i++) values[i]=i+1;
        assertEquals(99,JVectorMetrics.nearestRank(values,.99)); assertEquals(50,JVectorMetrics.nearestRank(values,.5));
        assertEquals(100.0,JVectorMetrics.latency(values,1_000_000_000L).get("qps"));
        assertThrows(IllegalArgumentException.class,()->JVectorMetrics.nearestRank(new long[0],.99));
        assertThrows(IllegalArgumentException.class,()->new JVectorIndexFactory.BuildParameters(32,100,1.2f,1.2f,true,true,"PQ",1,1));
    }
}
