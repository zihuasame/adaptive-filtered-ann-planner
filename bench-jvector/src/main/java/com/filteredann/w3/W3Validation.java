package com.filteredann.w3;

import com.filteredann.dataset.Dataset;
import com.filteredann.filter.FilterBitmap;
import com.filteredann.index.SearchResult.Neighbor;
import java.util.*;

/** Used only after timing. Ties use actual double squared L2 and zero tolerance. */
public final class W3Validation {
    public record Quality(double strict,double tieAware,double fixedK,double fulfillment,int missing,double boundary,double[] distances,boolean[] strictHits,boolean[] tieHits) { }
    public static Quality validate(Dataset base,float[] query,FilterBitmap filter,int[] ids,List<Neighbor> gt) {
        int m=Math.min(10,filter.cardinality());
        if(gt.size()!=m || ids.length>m) throw new IllegalStateException("Result/GT size invalid");
        Set<Integer> unique=new HashSet<>(),truth=new HashSet<>();
        gt.forEach(n->truth.add(n.vectorId()));
        double boundary=m==0?Double.NaN:gt.getLast().squaredL2();
        double[] distances=new double[ids.length]; boolean[] strictHits=new boolean[ids.length],tieHits=new boolean[ids.length]; int strict=0,tie=0;
        for(int i=0;i<ids.length;i++) {
            int id=ids[i]; if(id<0 || id>=base.count() || !unique.add(id) || !filter.get(id)) throw new IllegalStateException("Illegal, duplicate, or nonmatching result: "+id);
            distances[i]=base.squaredL2(query,id); strictHits[i]=truth.contains(id); tieHits[i]=distances[i]<=boundary;
            if(strictHits[i]) strict++; if(tieHits[i]) tie++;
        }
        return new Quality(m==0?Double.NaN:(double)strict/m,m==0?Double.NaN:(double)tie/m,(double)strict/10,m==0?Double.NaN:(double)ids.length/m,m-ids.length,boundary,distances,strictHits,tieHits);
    }
    public static long quantile(long[] values,double p) {
        if(values.length==0 || p<=0 || p>1) throw new IllegalArgumentException("Empty quantile/domain");
        long[] sorted=values.clone(); Arrays.sort(sorted); return sorted[(int)Math.ceil(p*sorted.length)-1];
    }
}
