package com.filteredann.w3resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.filteredann.dataset.*;
import com.filteredann.filter.FilterBitmap;
import com.filteredann.index.SearchResult.Neighbor;
import com.filteredann.jvector.*;
import com.filteredann.util.Artifacts;
import com.filteredann.w3.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;

/** Resource-only acceptance, not screening. Frozen strategies are reused unchanged. */
public final class ResourceAcceptance {
    static void unit(String id) { System.out.println("W3UNIT,START,"+id); }
    static void end(String id) { System.out.println("W3UNIT,END,"+id); }
    static long[] gc() {
        long count=0,millis=0;
        for(var bean:ManagementFactory.getGarbageCollectorMXBeans()) { count+=bean.getCollectionCount(); millis+=bean.getCollectionTime(); }
        return new long[]{count,millis};
    }
    static FilterBitmap bitmap(Path path) throws Exception {
        byte[] packed=Files.readAllBytes(path);
        if(packed.length!=125000) throw new IllegalArgumentException("Bitmap domain");
        int count=0; for(byte value:packed) count+=Integer.bitCount(value&255);
        int[] ids=new int[count]; int position=0;
        for(int i=0;i<1000000;i++) if((packed[i/8]&(1<<(i%8)))!=0) ids[position++]=i;
        return FilterBitmap.of(1000000,ids);
    }
    static List<Neighbor> truth(Path path,int queryId) throws Exception {
        List<Neighbor> result=new ArrayList<>();
        try(var lines=Files.lines(path)) {
            lines.skip(1).forEach(line->{String[] row=line.split(",");
                if(Integer.parseInt(row[1])==queryId) {
                    if(Integer.parseInt(row[2])!=result.size()) throw new IllegalStateException("GT rank");
                    result.add(new Neighbor(Integer.parseInt(row[3]),Double.parseDouble(row[4])));
                }
            });
        }
        if(result.size()!=10) throw new IllegalStateException("GT count");
        return result;
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]),run=Path.of(args[1]),prepared=Path.of(args[2]);
        var config=new ObjectMapper(new YAMLFactory()).readTree(root.resolve("config/w3-resource.yaml").toFile());
        if(!config.get("protocol").asText().equals("w3-resource-v1") || config.get("baseCount").asInt()!=1000000
            || config.get("k").asInt()!=10) throw new IllegalArgumentException("Protocol config");
        W3Telemetry.start(); unit("runtime-and-load"); long[] initialGc=gc();
        Artifacts.json(run.resolve("runtime-info.json"),JVectorRuntimeInfo.capture(root.resolve(".tools/jvector-4.0.2/jvector-4.0.2-sources.jar")));
        long started=System.nanoTime();
        Dataset base=new SiftDatasetReader().readVectors(root.resolve("data/raw/sift_base.fvecs"),1000000,128);
        long readNanos=System.nanoTime()-started; long convert=System.nanoTime();
        var store=new JVectorVectorStore(base); long conversionNanos=System.nanoTime()-convert;
        store.verifyMapping(base,1000,20261114);
        Artifacts.json(run.resolve("load-performance.json"),Map.of("readNanos",readNanos,"conversionNanos",conversionNanos,
            "originalFloatBytes",512000000L,"convertedVectorLibraryBytes",store.libraryVectorBytes(),"mappingSamples",1000));
        System.gc(); end("runtime-and-load"); unit("million-build");
        try(var built=new JVectorIndexFactory().build(store,JVectorIndexFactory.BuildParameters.baseline(),null)) {
            System.gc(); long[] buildGc=gc();
            Artifacts.json(run.resolve("build-performance.json"),Map.of("buildId",run.getFileName()+"-acceptance-graph",
                "structure",built.structure(),"performance",built.performance(),"gcCount",buildGc[0]-initialGc[0],"gcMillis",buildGc[1]-initialGc[1],
                "policy","Explicit initialization GC only; no cache eviction claim"));
            end("million-build"); unit("representative-queries");
            int queryId=Artifacts.JSON.readTree(prepared.resolve("labels-manifest.json").toFile()).get("queryOrder").get(0).asInt();
            Dataset queries=new SiftDatasetReader().readVectors(root.resolve("data/raw/sift_query.fvecs"),10000,128);
            List<Map<String,Object>> rows=new ArrayList<>();
            try(var searcher=new W3Strategies(base,store,built.graph())) {
                for(var name:config.get("filters")) {
                    String id=name.asText(); var filter=bitmap(prepared.resolve("bitmaps/"+id+".bits"));
                    var gt=truth(prepared.resolve("gt/"+id+".csv"),queryId);
                    for(var strategy:W3Strategies.Strategy.values()) {
                        var outcome=searcher.search(queries,queryId,()->filter,strategy,strategy==W3Strategies.Strategy.E?0:10,10);
                        var quality=W3Validation.validate(base,queries.vector(queryId),filter,outcome.ids(),gt);
                        if(strategy==W3Strategies.Strategy.E && quality.strict()!=1) throw new IllegalStateException("Exact mismatch");
                        rows.add(Map.of("filter",id,"strategy",strategy,"queryId",queryId,"matches",filter.cardinality(),
                            "outcome",outcome,"quality",quality));
                    }
                }
            }
            Artifacts.json(run.resolve("representative-results.json"),rows); end("representative-queries");
        }
    }
}
