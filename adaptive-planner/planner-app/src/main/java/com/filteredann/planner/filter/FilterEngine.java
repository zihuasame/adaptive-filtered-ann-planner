// SPDX-License-Identifier: Apache-2.0
package com.filteredann.planner.filter;

import com.filteredann.filter.FilterBitmap;
import com.filteredann.metadata.MetadataStore;
import java.util.*;

/** Bounded LRU of immutable W1 bitmaps. Cache misses are explicit to the planner. */
public final class FilterEngine {
    public enum CacheState { HOT, COLD }
    public record Prepared(FilterSpec spec,FilterBitmap bitmap,String bitmapSha256,int matches,double selectivity,CacheState cacheState,long acquisitionNanos) { }
    private final MetadataStore metadata;
    private final int[] clusterRanks;
    private final int count,capacity;
    private final Map<String,Prepared> cache=new LinkedHashMap<>(16,.75f,true);
    public FilterEngine(MetadataStore metadata,int[] clusterRanks,int count,int capacity) {
        if(count<1 || count>metadata.count() || count>clusterRanks.length || capacity<1)throw new IllegalArgumentException("Filter domain/cache capacity");
        this.metadata=metadata;this.clusterRanks=clusterRanks.clone();this.count=count;this.capacity=capacity;
    }
    public synchronized Prepared acquire(FilterSpec spec,boolean bypassCache) {
        Objects.requireNonNull(spec);long begin=System.nanoTime();
        if(!bypassCache) {
            Prepared hit=cache.get(spec.signature());
            if(hit!=null)return new Prepared(spec,hit.bitmap,hit.bitmapSha256,hit.matches,hit.selectivity,CacheState.HOT,System.nanoTime()-begin);
        }
        int[] ids=new int[count];int found=0;
        for(int id=0;id<count;id++)if(matches(spec,id))ids[found++]=id;
        var bitmap=FilterBitmap.of(count,Arrays.copyOf(ids,found));
        Prepared value=new Prepared(spec,bitmap,hash(bitmap),found,(double)found/count,CacheState.COLD,System.nanoTime()-begin);
        if(!bypassCache) {
            cache.put(spec.signature(),value);
            while(cache.size()>capacity)cache.remove(cache.keySet().iterator().next());
        }
        return value;
    }
    private boolean matches(FilterSpec spec,int id) {
        for(var c:spec.conditions()) {
            int value=switch(c.field()) {case RANDOM_RANK->metadata.filterRanks()[id];case CATEGORY->metadata.categories()[id];case REGION->metadata.regions()[id];case CLUSTER_RANK->clusterRanks[id];};
            if(c.operator()==FilterSpec.Operator.EQ?value!=c.value():value>=c.value())return false;
        }
        return true;
    }
    public synchronized void clear() { cache.clear(); }
    public int count() { return count; }
    public static String hash(FilterBitmap bitmap) {
        // Reuse the actual frozen packed-bit encoding, including the last partial byte.
        return com.filteredann.w3.W3Workload.bitmapHash(bitmap);
    }
}
