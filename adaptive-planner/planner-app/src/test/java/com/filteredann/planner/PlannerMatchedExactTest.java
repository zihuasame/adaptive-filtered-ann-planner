// SPDX-License-Identifier: Apache-2.0
package com.filteredann.planner;

import com.filteredann.planner.engine.*;
import com.filteredann.filter.FilterBitmap;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlannerMatchedExactTest {
    @Test void exactBoundaryTiesDuplicatesAndSmallFilters() {
        var rows=RowsVectorStore.of(new float[][]{{1,0},{-1,0},{0,1},{0,-1},{0,0},{0,0}});
        var index=new MatchedIdExactIndex(rows);
        var filter=FilterBitmap.all(6);
        assertEquals(List.of(4,5,0),index.search(new float[]{0,0},filter,3).neighbors().stream().map(n->n.vectorId()).toList());
        assertEquals(List.of(4,5,0),index.search(new float[]{0,0},filter,3).neighbors().stream().map(n->n.vectorId()).toList());
        assertTrue(index.search(new float[]{0,0},FilterBitmap.of(6),10).neighbors().isEmpty());
        assertEquals(1,index.search(new float[]{0,0},FilterBitmap.of(6,2),10).neighbors().size());
        assertEquals(6,index.search(new float[]{0,0},filter,100).neighbors().size());
    }
    @Test void cachedAndUncachedInputsMatchOriginalIncludingDistanceBits() {
        Random random=new Random(20261119);float[][] data=new float[513][17];
        for(var row:data)for(int d=0;d<row.length;d++)row[d]=random.nextInt(256);
        var rows=RowsVectorStore.of(data);var old=new ProductExactIndex(rows);var optimized=new MatchedIdExactIndex(rows);
        for(int f=0;f<8;f++) {
            int[] ids=new int[513];int n=0;for(int id=0;id<513;id++)if(random.nextInt(8)<=f)ids[n++]=id;
            var filter=FilterBitmap.of(513,Arrays.copyOf(ids,n));
            for(int q=0;q<32;q++)for(int k:new int[]{1,10,600}) {
                float[] query=rows.vector((q*17)%513);
                assertEquals(old.search(query,filter,k).neighbors(),optimized.search(query,filter,k).neighbors());
            }
        }
    }
    @Test void invalidDomainAndNonfiniteInputsStillFail() {
        var index=new MatchedIdExactIndex(RowsVectorStore.of(new float[][]{{0,1},{1,0}}));
        assertThrows(IllegalArgumentException.class,()->index.search(new float[]{0,0},FilterBitmap.all(2),0));
        assertThrows(IllegalArgumentException.class,()->index.search(new float[]{0},FilterBitmap.all(2),10));
        assertThrows(IllegalArgumentException.class,()->index.search(new float[]{Float.NaN,0},FilterBitmap.all(2),10));
        assertThrows(IllegalArgumentException.class,()->index.search(new float[]{0,0},FilterBitmap.all(3),10));
    }
}
