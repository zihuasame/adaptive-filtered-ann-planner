package com.filteredann.w3full;

import com.filteredann.dataset.*;
import com.filteredann.metadata.*;
import com.filteredann.w3.*;
import com.filteredann.util.Artifacts;
import java.nio.file.*;
import java.util.*;

/** Each GT shard is a separate process; the original W1 writing harness is never invoked. */
public final class FullPreparation {
    public static final int N=1000000,Q=10000,D=128;
    public static MetadataStore metadata(Path root) throws Exception {
        Path path=root.resolve("data/processed/metadata/sift1m-random-v1.bin");
        if(!Artifacts.sha256(path).equals("1591e21cd325201615f87d0092d266d04f95f3ff07f1c18642038f26eab2d44c")) throw new IllegalStateException("Frozen metadata mismatch");
        return new MetadataGenerator().read(path,20261113);
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]),run=Path.of(args[1]); String mode=args[2]; W3Telemetry.start();
        Dataset base=new SiftDatasetReader().readVectors(root.resolve("data/raw/sift_base.fvecs"),N,D);
        var metadata=metadata(root); Path prepared=run.resolve("prepared");
        if(mode.equals("labels")) {
            Files.createDirectory(prepared); long start=System.nanoTime(); var labels=W3Workload.labels(base); long nanos=System.nanoTime()-start;
            W3Workload.writeLabels(prepared.resolve("labels.bin"),labels); var filters=W3Workload.filters(metadata,labels);
            Artifacts.json(prepared.resolve("filters.json"),filters.stream().map(W3Workload.Filter::manifest).toList());
            int[] sizes=new int[16]; double[] sums=new double[16];
            for(int i=0;i<N;i++) { sizes[labels.cluster()[i]]++; sums[labels.cluster()[i]]+=labels.anchorDistance()[i]; }
            var order=Artifacts.JSON.readTree(root.resolve("results/w2/generated/build-manifest.json").toFile()).get("queryOrder");
            Artifacts.json(prepared.resolve("labels-manifest.json"),Map.of("baseCount",N,"queryCount",Q,"anchors",labels.anchors(),"clusterOrder",labels.clusterOrder(),
                "clusterSizes",sizes,"clusterSquaredDistanceSums",sums,"generationNanos",nanos,"labelHash",Artifacts.sha256(prepared.resolve("labels.bin")),
                "filtersHash",Artifacts.sha256(prepared.resolve("filters.json")),"queryOrder",order));
        } else if(mode.equals("gt")) {
            var manifest=Artifacts.JSON.readTree(prepared.resolve("labels-manifest.json").toFile());
            if(!Artifacts.sha256(prepared.resolve("labels.bin")).equals(manifest.get("labelHash").asText())) throw new IllegalStateException("Labels changed");
            var labels=W3Workload.readLabels(prepared.resolve("labels.bin"),N);
            var filter=W3Workload.filters(metadata,labels).stream().filter(f->f.id().equals(args[3])).findFirst().orElseThrow();
            Dataset queries=new SiftDatasetReader().readVectors(root.resolve("data/raw/sift_query.fvecs"),Q,D);
            var shard=W3GroundTruthExport.write(prepared.resolve("gt"),base,queries,filter);
            Artifacts.json(prepared.resolve("gt/"+filter.id()+".manifest.json"),shard);
        } else throw new IllegalArgumentException("labels or gt required");
    }
}
