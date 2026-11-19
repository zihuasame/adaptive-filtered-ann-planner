package com.filteredann.jvector;

import com.filteredann.dataset.Dataset;
import com.filteredann.evaluation.GroundTruthGenerator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JVectorRecallTest {
    @Test void exhaustiveBudgetMatchesExistingW1GroundTruth() throws Exception {
        Dataset base=JVectorIndexTest.randomData(300,16); var store=new JVectorVectorStore(base);
        GroundTruthGenerator gt=new GroundTruthGenerator(base);
        try(var built=new JVectorIndexFactory().build(store,JVectorIndexFactory.BuildParameters.baseline(),null);
            var searcher=new JVectorSearchIndex(base,store,built.graph(),300)) {
            for(int id=0;id<10;id++) {
                float[] query=base.vector(id);
                int[] truth=gt.global(query,10).neighbors().stream().mapToInt(n->n.vectorId()).toArray();
                var eval=JVectorMetrics.evaluate(base,query,searcher.search(query,new JVectorSearchOptions(10,300)),truth,10);
                assertEquals(1,eval.strictRecall()); assertEquals(1,eval.tieAwareRecall());
            }
        }
    }
    @Test void boundaryTiesHaveZeroToleranceAndDoNotExcuseWorseNeighbors() {
        Dataset base=new Dataset(new float[]{0,1,-1,2},1);
        var tied=new JVectorSearchIndex.Outcome(new int[]{0,2},new float[]{1,.5f},0,0,0,0,0,0,0,0,0);
        var eval=JVectorMetrics.evaluate(base,new float[]{0},tied,new int[]{0,1},2);
        assertEquals(.5,eval.strictRecall()); assertEquals(1,eval.tieAwareRecall()); assertEquals(1,eval.boundaryDistance());
        var worse=new JVectorSearchIndex.Outcome(new int[]{0,3},new float[]{1,.2f},0,0,0,0,0,0,0,0,0);
        assertEquals(.5,JVectorMetrics.evaluate(base,new float[]{0},worse,new int[]{0,1},2).tieAwareRecall());
    }
}
