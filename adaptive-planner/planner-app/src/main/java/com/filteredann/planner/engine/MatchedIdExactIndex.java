// SPDX-License-Identifier: Apache-2.0
package com.filteredann.planner.engine;

import com.filteredann.filter.FilterBitmap;
import com.filteredann.index.*;
import com.filteredann.index.SearchResult.Neighbor;
import java.util.*;

/** Same exact double-L2/heap/tie rules; amortize the immutable bitmap's N membership checks. */
public final class MatchedIdExactIndex implements SearchIndex {
    private final RowsVectorStore dataset;
    // Weak keys do not keep evicted filter bitmaps alive. Values contain only integer IDs.
    private final Map<FilterBitmap,int[]> matchingIds=new WeakHashMap<>();
    private boolean hooks=true;
    public MatchedIdExactIndex(RowsVectorStore dataset){this.dataset=Objects.requireNonNull(dataset);}
    public void timingHooks(boolean enabled){hooks=enabled;}
    private long clock(){return hooks?System.nanoTime():0L;}
    @Override public String name(){return "exact-squared-l2-cached-matching-ids";}
    @Override public SearchResult search(float[] query,FilterBitmap filter,int k) {
        if(k<=0)throw new IllegalArgumentException("K must be positive");
        if(query==null||query.length!=dataset.dimensions())throw new IllegalArgumentException("Query dimension mismatch");
        for(float x:query)if(!Float.isFinite(x))throw new IllegalArgumentException("Non-finite query");
        if(filter==null||filter.length()!=dataset.count())throw new IllegalArgumentException("Bitmap length mismatch");
        long start=clock();int[] ids=ids(filter);
        int capacity=Math.min(k,dataset.count());
        PriorityQueue<Neighbor> heap=new PriorityQueue<>(Math.max(1,capacity),SearchResult.ORDER.reversed());
        for(int id:ids) {
            double distance=dataset.squaredL2(query,id);
            if(heap.size()<capacity)heap.add(new Neighbor(id,distance));
            else {
                Neighbor worst=heap.peek();
                if(distance<worst.squaredL2()||distance==worst.squaredL2()&&id<worst.vectorId()) {
                    heap.remove();heap.add(new Neighbor(id,distance));
                }
            }
        }
        List<Neighbor> result=new ArrayList<>(heap);result.sort(SearchResult.ORDER);
        return new SearchResult(result,new SearchStats(ids.length,ids.length,clock()-start));
    }
    private int[] ids(FilterBitmap filter) {
        synchronized(matchingIds) {
            int[] cached=matchingIds.get(filter);if(cached!=null)return cached;
        }
        int[] built=new int[filter.cardinality()];int count=0;
        for(int id=0;id<dataset.count();id++)if(filter.get(id))built[count++]=id;
        synchronized(matchingIds){matchingIds.put(filter,built);}
        return built;
    }
}
