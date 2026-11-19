package com.filteredann.w3;

import com.filteredann.dataset.Dataset;
import com.filteredann.filter.*;
import com.filteredann.metadata.MetadataStore;
import com.filteredann.util.Artifacts;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Deterministic base-only labels. Smoke thresholds scale to smoke N; formal N is fixed. */
public final class W3Workload {
    public record Labels(int[] anchors,int[] cluster,int[] rank,double[] anchorDistance,int[] clusterOrder) { }
    public record Filter(String id,String text,String family,FilterPredicate left,FilterPredicate right,
                         FilterBitmap bitmap,Map<String,Object> manifest) {
        public FilterBitmap build(MetadataStore metadata) { return FilterBitmap.build(metadata,left.and(right)); }
    }
    public static int[] permutation(int n,long seed) {
        int[] ids=new int[n]; for(int i=0;i<n;i++) ids[i]=i;
        Random random=new Random(seed);
        for(int i=n-1;i>0;i--) { int j=random.nextInt(i+1),v=ids[i]; ids[i]=ids[j]; ids[j]=v; }
        return ids;
    }
    public static Labels labels(Dataset base) {
        int n=base.count(); if(n<16) throw new IllegalArgumentException("16 base anchors required");
        int[] anchors=Arrays.copyOf(permutation(n,20261114),16),cluster=new int[n],rank=new int[n];
        double[] distance=new double[n]; Arrays.fill(distance,Double.POSITIVE_INFINITY);
        for(int c=0;c<16;c++) {
            float[] anchor=base.vector(anchors[c]);
            for(int id=0;id<n;id++) {
                double d=base.squaredL2(anchor,id);
                if(d<distance[id]) { distance[id]=d; cluster[id]=c; }
            }
        }
        Integer[] order=new Integer[16]; for(int c=0;c<16;c++) order[c]=c;
        float[] first=base.vector(anchors[0]);
        Arrays.sort(order,Comparator.<Integer>comparingDouble(c->base.squaredL2(first,anchors[c])).thenComparingInt(c->anchors[c]));
        int[] clusterOrder=Arrays.stream(order).mapToInt(Integer::intValue).toArray(),position=new int[16];
        for(int i=0;i<16;i++) position[clusterOrder[i]]=i;
        Integer[] ids=new Integer[n]; for(int i=0;i<n;i++) ids[i]=i;
        Arrays.sort(ids,Comparator.<Integer>comparingInt(id->position[cluster[id]]).thenComparingDouble(id->distance[id]).thenComparingInt(id->id));
        for(int i=0;i<n;i++) rank[ids[i]]=i;
        return new Labels(anchors,cluster,rank,distance,clusterOrder);
    }
    public static String bitmapHash(FilterBitmap bitmap) {
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            for(int i=0;i<bitmap.length();i+=8) { int b=0; for(int j=0;j<8 && i+j<bitmap.length();j++) if(bitmap.get(i+j)) b|=1<<j; digest.update((byte)b); }
            return HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public static List<Filter> filters(MetadataStore metadata,Labels labels) {
        int n=metadata.count(); if(labels.rank.length!=n) throw new IllegalArgumentException("Label domain");
        List<Filter> filters=new ArrayList<>(); FilterPredicate all=(m,id)->true;
        for(String family:List.of("R","C")) {
            for(int t:new int[]{500000,200000,100000,50000,10000,1000}) {
                int limit=(int)((long)t*n/1000000);
                FilterPredicate a=family.equals("R")?FilterPredicate.rankBelow(limit):(m,id)->labels.rank[id]<limit;
                add(filters,metadata,family+"-rank-"+t,family+" rank<"+limit,family,a,all);
            }
            if(family.equals("R")) {
                for(int t:new int[]{1000000,800000,160000,16000}) {
                    int limit=(int)((long)t*n/1000000);
                    add(filters,metadata,"R-category0-rank-"+t,"category=0 AND rank<"+limit,"R",FilterPredicate.category(0),FilterPredicate.rankBelow(limit));
                }
            } else {
                for(int t:new int[]{500000,100000,10000}) {
                    int a=n/10,b=(int)((long)t*n/1000000);
                    add(filters,metadata,"C-rank100000-random-"+t,"cluster_rank<"+a+" AND random_rank<"+b,"C",(m,id)->labels.rank[id]<a,FilterPredicate.rankBelow(b));
                }
            }
        }
        if(filters.size()!=19) throw new AssertionError("19 filters required");
        return List.copyOf(filters);
    }
    private static void add(List<Filter> list,MetadataStore m,String id,String text,String family,FilterPredicate a,FilterPredicate b) {
        var left=FilterBitmap.build(m,a); var right=FilterBitmap.build(m,b); var both=left.and(right);
        var direct=FilterBitmap.build(m,a.and(b));
        if(!bitmapHash(direct).equals(bitmapHash(both))) throw new AssertionError("Direct/AND mismatch");
        list.add(new Filter(id,text,family,a,b,both,Map.of("id",id,"predicate",text,"family",family,"baseCount",m.count(),
            "leftCount",left.cardinality(),"rightCount",right.cardinality(),"intersectionCount",both.cardinality(),
            "leftHash",bitmapHash(left),"rightHash",bitmapHash(right),"intersectionHash",bitmapHash(both))));
    }
    public static void writeLabels(Path output,Labels labels) throws IOException {
        try(var stream=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(output,StandardOpenOption.CREATE_NEW)))) {
            stream.writeUTF("W3CLUSTER1"); stream.writeInt(labels.rank.length);
            for(int id:labels.anchors) stream.writeInt(id);
            for(int id:labels.clusterOrder) stream.writeInt(id);
            for(int id=0;id<labels.rank.length;id++) { stream.writeInt(labels.cluster[id]); stream.writeInt(labels.rank[id]); stream.writeDouble(labels.anchorDistance[id]); }
        }
    }
    public static Labels readLabels(Path input,int expectedCount) throws IOException {
        try(var stream=new DataInputStream(new BufferedInputStream(Files.newInputStream(input)))) {
            if(!stream.readUTF().equals("W3CLUSTER1") || stream.readInt()!=expectedCount) throw new IOException("Label header/domain mismatch");
            int[] anchors=new int[16],order=new int[16],cluster=new int[expectedCount],rank=new int[expectedCount]; double[] distances=new double[expectedCount];
            for(int i=0;i<16;i++) anchors[i]=stream.readInt(); for(int i=0;i<16;i++) order[i]=stream.readInt();
            boolean[] seen=new boolean[expectedCount];
            for(int id=0;id<expectedCount;id++) {
                cluster[id]=stream.readInt(); rank[id]=stream.readInt(); distances[id]=stream.readDouble();
                if(cluster[id]<0 || cluster[id]>=16 || rank[id]<0 || rank[id]>=expectedCount || seen[rank[id]] || !Double.isFinite(distances[id])) throw new IOException("Invalid label row");
                seen[rank[id]]=true;
            }
            if(stream.read()!=-1) throw new IOException("Trailing label bytes");
            return new Labels(anchors,cluster,rank,distances,order);
        }
    }
}
