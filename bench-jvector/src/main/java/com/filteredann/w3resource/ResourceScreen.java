package com.filteredann.w3resource;

import com.filteredann.dataset.*;
import com.filteredann.jvector.*;
import com.filteredann.util.Artifacts;
import com.filteredann.w3.*;
import java.nio.file.*;
import java.util.*;

/** Sequential preregistered screening; bounded one-cell outcomes, no GT in query timing. */
public final class ResourceScreen {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]),run=Path.of(args[1]),prepared=Path.of(args[2]);
        W3Telemetry.start(); ResourceAcceptance.unit("load");
        Artifacts.json(run.resolve("screen-runtime.json"),JVectorRuntimeInfo.capture(root.resolve(".tools/jvector-4.0.2/jvector-4.0.2-sources.jar")));
        var base=new SiftDatasetReader().readVectors(root.resolve("data/raw/sift_base.fvecs"),1000000,128);
        var store=new JVectorVectorStore(base); store.verifyMapping(base,1000,20261114);
        var queries=new SiftDatasetReader().readVectors(root.resolve("data/raw/sift_query.fvecs"),10000,128);
        var manifest=Artifacts.JSON.readTree(prepared.resolve("labels-manifest.json").toFile());
        List<Map<String,Object>> schedule=Artifacts.JSON.readValue(run.resolve("screen-schedule.json").toFile(),List.class);
        System.gc(); ResourceAcceptance.end("load"); ResourceAcceptance.unit("screen-build");
        try(var built=new JVectorIndexFactory().build(store,JVectorIndexFactory.BuildParameters.baseline(),null)) {
            System.gc();
            Artifacts.json(run.resolve("screen-build.json"),Map.of("buildId",run.getFileName()+"-screen-graph","performance",built.performance(),"structure",built.structure()));
            ResourceAcceptance.end("screen-build");
            try(var searcher=new W3Strategies(base,store,built.graph())) {
                for(var cell:schedule) {
                    String id=(String)cell.get("id"),filterId=(String)cell.get("filter");
                    var strategy=W3Strategies.Strategy.valueOf((String)cell.get("strategy"));
                    int budget=cell.get("budget")==null?0:((Number)cell.get("budget")).intValue();
                    var filter=ResourceAcceptance.bitmap(prepared.resolve("bitmaps/"+filterId+".bits"));
                    if(strategy==W3Strategies.Strategy.N && budget>filter.cardinality()) throw new IllegalArgumentException("N exceeds matches");
                    ResourceAcceptance.unit(id);
                    for(int i=0;i<20;i++) searcher.search(queries,manifest.get("queryOrder").get(i).asInt(),()->filter,strategy,budget,Math.max(10,budget));
                    W3Strategies.Outcome[] outcomes=new W3Strategies.Outcome[100];
                    long start=System.nanoTime();
                    for(int i=0;i<100;i++) outcomes[i]=searcher.search(queries,manifest.get("queryOrder").get(i).asInt(),()->filter,strategy,budget,Math.max(10,budget));
                    long wall=System.nanoTime()-start;
                    var row=Map.of("cell",cell,"buildId",run.getFileName()+"-screen-graph","matches",filter.cardinality(),"wallNanos",wall,"outcomes",outcomes);
                    Path target=run.resolve("screen-cells/"+id+".json"),partial=target.resolveSibling(id+".partial");
                    Artifacts.json(partial,row); Files.move(partial,target,StandardCopyOption.ATOMIC_MOVE);
                    ResourceAcceptance.end(id);
                }
            }
        }
    }
}
