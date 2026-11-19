package com.filteredann.w3;

import com.filteredann.dataset.*;
import com.filteredann.metadata.*;
import com.filteredann.evaluation.GroundTruthGenerator;
import com.filteredann.filter.FilterBitmap;
import com.filteredann.index.SearchResult.Neighbor;
import com.filteredann.jvector.*;
import com.filteredann.util.Artifacts;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Stage 1 only. All output explicitly marked 100k/100q smoke, never formal SIFT1M. */
public final class W3Smoke {
    static final int[] GRID={10,20,40,80,160,320,640,1280};
    public static Dataset prefix(Path file,int n,int dimension) throws IOException {
        if(Files.size(file)%(4L*(dimension+1))!=0 || Files.size(file)<n*4L*(dimension+1)) throw new IOException("Dataset truncated/domain");
        float[] values=new float[n*dimension]; byte[] row=new byte[4*(dimension+1)];
        try(var input=new BufferedInputStream(Files.newInputStream(file))) {
            var buffer=ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN);
            for(int id=0;id<n;id++) {
                if(input.readNBytes(row,0,row.length)!=row.length) throw new EOFException(); buffer.clear();
                if(buffer.getInt()!=dimension) throw new IOException("Dimension at "+id);
                for(int d=0;d<dimension;d++) values[id*dimension+d]=buffer.getFloat();
            }
        }
        return new Dataset(values,dimension);
    }
    public static MetadataStore projectedMetadata(MetadataStore full,int n) {
        Integer[] ids=new Integer[n]; for(int i=0;i<n;i++) ids[i]=i;
        Arrays.sort(ids,Comparator.comparingInt(id->full.filterRanks()[id])); int[] ranks=new int[n];
        for(int i=0;i<n;i++) ranks[ids[i]]=i;
        return new MetadataStore(ranks,Arrays.copyOf(full.categories(),n),Arrays.copyOf(full.regions(),n));
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]),out=Path.of(args[1]); Files.createDirectory(out); W3Telemetry.start();
        Artifacts.json(out.resolve("status.json"),Map.of("status","running","scope","smoke-only-100000-base-100-query"));
        long start=System.nanoTime();
        JVectorRuntimeInfo.requirePanama();
        Path metadataPath=root.resolve("data/processed/metadata/sift1m-random-v1.bin");
        if(!Artifacts.sha256(metadataPath).equals("1591e21cd325201615f87d0092d266d04f95f3ff07f1c18642038f26eab2d44c")) throw new IllegalStateException("Frozen metadata mismatch");
        Dataset base=prefix(root.resolve("data/raw/sift_base.fvecs"),100000,128),queries=prefix(root.resolve("data/raw/sift_query.fvecs"),100,128);
        var metadata=projectedMetadata(new MetadataGenerator().read(metadataPath,20261113),base.count());
        long tagStart=System.nanoTime(); var labels=W3Workload.labels(base); long tagNs=System.nanoTime()-tagStart;
        W3Workload.writeLabels(out.resolve("smoke-labels.bin"),labels);
        var filters=W3Workload.filters(metadata,labels);
        Artifacts.json(out.resolve("filter-manifest.json"),filters.stream().map(W3Workload.Filter::manifest).toList());
        Artifacts.json(out.resolve("smoke-inputs.json"),Map.of("baseCount",base.count(),"queryCount",queries.count(),"dimensions",128,
            "randomRanks",metadata.filterRanks(),"categories",metadata.categories(),"anchors",labels.anchors(),"clusterRanks",labels.rank(),
            "clusterIds",labels.cluster(),"clusterOrder",labels.clusterOrder(),"labelHash",Artifacts.sha256(out.resolve("smoke-labels.bin"))));
        var generator=new GroundTruthGenerator(base); List<List<List<Neighbor>>> allTruth=new ArrayList<>();
        long gtStart=System.nanoTime();
        try(var csv=Files.newBufferedWriter(out.resolve("gt.csv"))) {
            csv.write("filter_id,query_id,rank,vector_id,squared_l2\n");
            for(var filter:filters) {
                List<List<Neighbor>> truth=new ArrayList<>();
                for(int q=0;q<100;q++) {
                    var neighbors=generator.filtered(queries.vector(q),filter.bitmap(),10).neighbors(); truth.add(neighbors);
                    for(int rank=0;rank<neighbors.size();rank++) { var n=neighbors.get(rank); csv.write(filter.id()+","+q+","+rank+","+n.vectorId()+","+n.squaredL2()+"\n"); }
                }
                allTruth.add(truth);
            }
        }
        long gtNs=System.nanoTime()-gtStart;
        var store=new JVectorVectorStore(base); store.verifyMapping(base,1000,20261114);
        List<Map<String,Object>> cells=new ArrayList<>();
        try(var built=new JVectorIndexFactory().build(store,JVectorIndexFactory.BuildParameters.baseline(),null);
            var worker=new W3Strategies(base,store,built.graph());
            var latency=Files.newBufferedWriter(out.resolve("requests.csv"));
            var results=Files.newBufferedWriter(out.resolve("results.csv"));
            var estimates=Files.newBufferedWriter(out.resolve("budget-samples.csv"))) {
            Artifacts.json(out.resolve("build.json"),Map.of("performance",built.performance(),"structure",built.structure(),"baseCount",100000));
            latency.write("filter_id,strategy,query_id,budget,query_ns,score_ns,filter_ns,core_ns,conversion_ns,post_ns,e2e_ns,returned,visited,expanded,expanded_base,reranked,strict,tie,missing\n");
            results.write("filter_id,strategy,query_id,rank,vector_id,score,exact_squared_l2,strict_hit,tie_hit,boundary\n");
            estimates.write("filter_id,strategy,budget,query_id,e2e_ns,core_ns,visited,expanded\n");
            for(int f=0;f<filters.size();f++) {
                var filter=filters.get(f);
                for(var strategy:W3Strategies.Strategy.values()) {
                    for(int warm=0;warm<10;warm++) worker.search(queries.vector(warm),filter.bitmap(),strategy,40,40);
                    W3Strategies.Outcome[] outputs=new W3Strategies.Outcome[100];
                    long batchStart=System.nanoTime();
                    for(int q=0;q<100;q++) outputs[q]=worker.search(queries,q,filter::bitmap,strategy,40,40);
                    long wall=System.nanoTime()-batchStart; long[] times=new long[100]; double strict=0,tie=0;
                    for(int q=0;q<100;q++) {
                        var o=outputs[q]; var quality=W3Validation.validate(base,queries.vector(q),filter.bitmap(),o.ids(),allTruth.get(f).get(q));
                        if(strategy==W3Strategies.Strategy.E && quality.strict()!=1) throw new AssertionError("Exact/GT mismatch");
                        strict+=quality.strict(); tie+=quality.tieAware(); times[q]=o.endToEnd();
                        latency.write(filter.id()+","+strategy+","+q+",40,"+o.queryPreparation()+","+o.scorePreparation()+","+o.filterAcquisition()+","+o.coreSearch()+","+o.conversion()+","+o.postFilter()+","+o.endToEnd()+","+o.ids().length+","+o.visited()+","+o.expanded()+","+o.expandedBase()+","+o.reranked()+","+quality.strict()+","+quality.tieAware()+","+quality.missing()+"\n");
                        for(int rank=0;rank<o.ids().length;rank++) results.write(filter.id()+","+strategy+","+q+","+rank+","+o.ids()[rank]+","+o.scores()[rank]+","+quality.distances()[rank]+","+quality.strictHits()[rank]+","+quality.tieHits()[rank]+","+quality.boundary()+"\n");
                    }
                    cells.add(Map.of("filter",filter.id(),"strategy",strategy,"queries",100,"batchWallNs",wall,"p99Ns",W3Validation.quantile(times,.99),"strict",strict/100,"tie",tie/100));
                }
                // Diagnostic sample grid for cost projection, not a formal performance curve.
                for(var strategy:W3Strategies.Strategy.values()) for(int budget:strategy==W3Strategies.Strategy.E?new int[]{10}:GRID) {
                    for(int warm=0;warm<2;warm++) worker.search(queries.vector(warm),filter.bitmap(),strategy,budget,budget);
                    for(int q=0;q<5;q++) {
                        var o=worker.search(queries,q,filter::bitmap,strategy,budget,budget);
                        W3Validation.validate(base,queries.vector(q),filter.bitmap(),o.ids(),allTruth.get(f).get(q));
                        estimates.write(filter.id()+","+strategy+","+budget+","+q+","+o.endToEnd()+","+o.coreSearch()+","+o.visited()+","+o.expanded()+"\n");
                    }
                }
                latency.flush(); results.flush(); estimates.flush();
                System.out.println("Smoke completed filter "+filter.id());
            }
        }
        Artifacts.json(out.resolve("summary.json"),cells);
        Artifacts.json(out.resolve("status.json"),Map.of("status","completed","scope","smoke-only-100000-base-100-query","gtGroups",1900,"requestCount",7600,
            "gtNanos",gtNs,"tagNanos",tagNs,"totalNanos",System.nanoTime()-start,"metadataProjection","First 100k frozen category IDs; ranks compressed by frozen rank order; no formal data written"));
    }
}
