package com.filteredann;

import com.filteredann.dataset.*;
import com.filteredann.filter.*;
import com.filteredann.index.*;
import com.filteredann.index.SearchResult.Neighbor;
import com.filteredann.evaluation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SearchTest {
    private final Dataset data = new Dataset(new float[]{0,1,2,3,4,5},1);
    private final BruteForceIndex index = new BruteForceIndex(data);
    private final float[] query = {0};
    @Test void allRequiredKAndFilterBoundaries() {
        assertTrue(index.search(query,FilterBitmap.of(6),3).neighbors().isEmpty());
        for (int k:new int[]{2,3,10}) {
            var result=index.search(query,FilterBitmap.of(6,2,4),k);
            assertEquals(List.of(new Neighbor(2,4),new Neighbor(4,16)),result.neighbors());
            assertEquals(2,result.stats().distanceCalculations()); assertEquals(2,result.stats().predicateMatches());
        }
        for (int k:new int[]{0,-1}) assertThrows(IllegalArgumentException.class,()->index.search(query,FilterBitmap.all(6),k));
        assertThrows(IllegalArgumentException.class,()->index.search(query,FilterBitmap.all(5),3));
        assertThrows(IllegalArgumentException.class,()->index.search(new float[]{1,2},FilterBitmap.all(6),3));
        assertThrows(IllegalArgumentException.class,()->index.search(new float[]{Float.POSITIVE_INFINITY},FilterBitmap.all(6),3));
        assertTrue(new BruteForceIndex(new Dataset(new float[0],1)).search(query,FilterBitmap.all(0),3).neighbors().isEmpty());
    }
    @Test void tiesDuplicatesAndDoubleSubtraction() {
        var ties=new BruteForceIndex(new Dataset(new float[]{1,-1,1,-1},1)).search(query,FilterBitmap.all(4),3);
        assertEquals(List.of(new Neighbor(0,1),new Neighbor(1,1),new Neighbor(2,1)),ties.neighbors());
        var large=new BruteForceIndex(new Dataset(new float[]{-Float.MAX_VALUE},1)).search(new float[]{Float.MAX_VALUE},FilterBitmap.all(1),1);
        assertTrue(Double.isFinite(large.neighbors().getFirst().squaredL2()));
    }
    @Test void matchesIndependentFullSortAcrossRandomSmallCases() {
        Random random=new Random(1234);
        for(int trial=0;trial<100;trial++) {
            float[] values=new float[120]; for(int i=0;i<values.length;i++) values[i]=random.nextInt(7)-3;
            Dataset d=new Dataset(values,3); float[] q={1,2,3}; List<Integer> allowed=new ArrayList<>();
            List<Neighbor> expected=new ArrayList<>();
            for(int id=0;id<40;id++) if(random.nextBoolean()) {
                allowed.add(id); double sum=0; for(int j=0;j<3;j++) { double delta=(double)q[j]-values[id*3+j]; sum+=delta*delta; }
                expected.add(new Neighbor(id,sum));
            }
            expected.sort(SearchResult.ORDER);
            int k=random.nextInt(50)+1;
            var result=new BruteForceIndex(d).search(q,FilterBitmap.of(40,allowed.stream().mapToInt(Integer::intValue).toArray()),k);
            assertEquals(expected.subList(0,Math.min(k,expected.size())),result.neighbors());
            assertEquals(allowed.size(),result.stats().distanceCalculations());
        }
    }
    @Test void mathA_identityWithNonzeroIntersection() {
        checkMath(FilterBitmap.of(6,0,2,3,4),3,2.0/3,2.0/3);
    }
    @Test void mathB_exactPostfilterCanHaveZeroRecall() {
        FilterBitmap filter=FilterBitmap.of(6,3,4,5);
        checkMath(filter,3,0,0);
        GroundTruthGenerator gt=new GroundTruthGenerator(data);
        assertEquals(List.of(0,1,2),gt.global(query,3).neighbors().stream().map(Neighbor::vectorId).toList());
        assertEquals(List.of(3,4,5),gt.filtered(query,filter,3).neighbors().stream().map(Neighbor::vectorId).toList());
    }
    @Test void mathC_smallFilterChangesDenominator() {
        checkMath(FilterBitmap.of(6,0,5),3,1.0/3,0.5);
    }
    private void checkMath(FilterBitmap filter,int k,double globalRecall,double filteredRecall) {
        GroundTruthGenerator generator=new GroundTruthGenerator(data);
        var global=generator.global(query,k).neighbors(); var filtered=generator.filtered(query,filter,k).neighbors();
        var candidates=global.stream().filter(n->filter.get(n.vectorId())).toList();
        assertTrue(filtered.containsAll(candidates));
        assertEquals(globalRecall,RecallMetrics.recall(candidates,global).orElseThrow());
        assertEquals(filteredRecall,RecallMetrics.recall(candidates,filtered).orElseThrow());
        assertEquals(1,RecallMetrics.recall(index.search(query,filter,k).neighbors(),filtered).orElseThrow());
    }
    @Test void emptyGroundTruthIsNotEvaluableAndRetrievalDuplicatesDoNotInflateRecall() {
        assertTrue(RecallMetrics.recall(List.of(),List.of()).isEmpty());
        assertEquals(0.5,RecallMetrics.recall(List.of(new Neighbor(0,0),new Neighbor(0,0)),List.of(new Neighbor(0,0),new Neighbor(1,1))).orElseThrow());
        assertThrows(IllegalArgumentException.class,()->RecallMetrics.recall(List.of(),List.of(new Neighbor(0,0),new Neighbor(0,0))));
    }
}
