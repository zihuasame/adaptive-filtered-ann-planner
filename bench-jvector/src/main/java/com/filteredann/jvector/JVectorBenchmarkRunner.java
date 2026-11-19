package com.filteredann.jvector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.filteredann.dataset.*;
import com.filteredann.evaluation.GroundTruthGenerator;
import com.filteredann.index.SearchResult.Neighbor;
import com.filteredann.util.Artifacts;
import java.lang.ref.WeakReference;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

public final class JVectorBenchmarkRunner {
    public record Config(String jvectorVersion,String dataset,int baseCount,int queryCount,int dimensions,int k,
            JVectorIndexFactory.BuildParameters build,List<Integer> candidateKs,List<Integer> extensionCandidateKs,
            String queryIds,long queryOrderSeed,int warmupQueries,int formalQueries,int repeats,List<Integer> threadCounts,
            String provider,List<String> jvmArguments,List<Double> recallTargets,int rebuildQueries) {
        public Config {
            if(!"4.0.2".equals(jvectorVersion) || !"SIFT1M".equals(dataset) || baseCount!=1000000 || queryCount!=10000
                    || dimensions!=128 || k!=10 || formalQueries!=10000 || warmupQueries!=1000 || repeats!=3
                    || !"all".equals(queryIds) || !"panama".equals(provider) || rebuildQueries!=1000
                    || !build.equals(JVectorIndexFactory.BuildParameters.baseline()) || !threadCounts.equals(List.of(1,8))
                    || !candidateKs.equals(List.of(10,20,40,80,160,320,640,1280))
                    || !extensionCandidateKs.equals(List.of(2560,5120,10000))) throw new IllegalArgumentException("Frozen W2 baseline configuration differs from task");
        }
    }
    public record Batch(JVectorSearchIndex.Outcome[] outcomes,long elapsedNanos) { }
    public static Batch measure(Dataset base,Dataset queries,JVectorVectorStore store,
            io.github.jbellis.jvector.graph.ImmutableGraphIndex graph,int[] order,int candidateK,int warmup,int threads) throws Exception {
        if(order.length==0 || threads<1) throw new IllegalArgumentException("Empty batch/invalid threads");
        var outcomes=new JVectorSearchIndex.Outcome[order.length];
        CountDownLatch ready=new CountDownLatch(threads), start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures=new ArrayList<>();
            for(int worker=0;worker<threads;worker++) {
                final int workerId=worker;
                futures.add(executor.submit(()->{
                    try(var searcher=new JVectorSearchIndex(base,store,graph,candidateK)) {
                        var options=new JVectorSearchOptions(10,candidateK);
                        try { for(int i=workerId;i<warmup;i+=threads) searcher.search(queries,order[i%order.length],options); }
                        finally { ready.countDown(); }
                        start.await();
                        for(int pos=workerId;pos<order.length;pos+=threads) outcomes[pos]=searcher.search(queries,order[pos],options);
                    } catch(Exception e) { ready.countDown(); throw new RuntimeException(e); }
                }));
            }
            try {
                if(!ready.await(60,TimeUnit.SECONDS)) throw new IllegalStateException("Warmup workers did not become ready");
                long begin=System.nanoTime(); start.countDown();
                for(var future:futures) future.get();
                return new Batch(outcomes,System.nanoTime()-begin);
            } finally { start.countDown(); }
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=2 || !List.of("smoke","formal").contains(args[1])) throw new IllegalArgumentException("Usage: <project-root> smoke|formal");
        Path root=Path.of(args[0]).toAbsolutePath().normalize(); boolean smoke=args[1].equals("smoke");
        Path output=root.resolve(smoke?"results/w2-smoke/generated":"results/w2/generated"); Files.createDirectories(output);
        try {
            Config config=new ObjectMapper(new YAMLFactory()).readValue(root.resolve("config/w2-jvector.yaml").toFile(),Config.class);
            new JVectorBenchmarkRunner().run(root,output,config,smoke);
            Artifacts.json(output.resolve("status.json"),Map.of("complete",true,"mode",args[1]));
        } catch(Throwable e) {
            Artifacts.json(output.resolve("status.json"),Map.of("complete",false,"mode",args[1],"error",e.toString()));
            throw e;
        }
    }
    private void run(Path root,Path output,Config config,boolean smoke) throws Exception {
        Map<String,String> protectedFiles=protectW1(root);
        Map<String,Object> runtime=JVectorRuntimeInfo.capture(root.resolve(".tools/jvector-4.0.2/jvector-4.0.2-sources.jar"));
        Artifacts.json(output.resolve("runtime-info.json"),runtime);
        long available=JVectorRuntimeInfo.osBean().getFreeMemorySize();
        long required=smoke?1024L*1024*1024:3L*1024*1024*1024;
        Artifacts.json(output.resolve("memory-preflight.json"),Map.of("availablePhysicalBytes",available,"requiredAvailableBytes",required,
                "reason","Full: 1.024 GB vector payload plus objects, graph, builder scratch, allocation/OS margin. No hidden corpus reduction.","admitted",available>=required));
        if(available<required) throw new IllegalStateException("Insufficient available physical memory: "+available+"; required "+required);
        long totalStart=System.nanoTime();
        try(var monitor=new JVectorMetrics.MemoryMonitor()) {
            Map<String,String> expected=Artifacts.JSON.readValue(root.resolve("config/checksums.json").toFile(),new TypeReference<>(){});
            for(var e:expected.entrySet()) if(!Artifacts.sha256(root.resolve("data/raw/"+e.getKey())).equals(e.getValue())) throw new IllegalStateException("Dataset checksum mismatch: "+e.getKey());
            long loadStart=System.nanoTime(); SiftDatasetReader reader=new SiftDatasetReader();
            Dataset base=reader.readVectors(root.resolve("data/raw/sift_base.fvecs"),1000000,128);
            Dataset queries=reader.readVectors(root.resolve("data/raw/sift_query.fvecs"),10000,128);
            int[][] official=reader.readGroundTruth(root.resolve("data/raw/sift_groundtruth.ivecs"),10000,1000000);
            if(smoke) {
                float[] prefix=new float[100000*128];
                for(int id=0;id<100000;id++) System.arraycopy(base.vector(id),0,prefix,id*128,128);
                base=new Dataset(prefix,128);
            }
            long loadNanos=System.nanoTime()-loadStart;
            int queryCount=smoke?100:10000;
            int[] order=queryOrder(queryCount,config.queryOrderSeed());
            if(smoke) {
                official=new int[10000][]; GroundTruthGenerator exact=new GroundTruthGenerator(base);
                for(int id:order) official[id]=exact.global(queries.vector(id),10).neighbors().stream().mapToInt(Neighbor::vectorId).toArray();
            }
            Map<Integer,int[]> w1Truth=smoke?Map.of():w1Reference(root,base,queries,output);
            long conversionStart=System.nanoTime(); var store=new JVectorVectorStore(base);
            store.verifyMapping(base,1000,config.queryOrderSeed());
            long conversionNanos=System.nanoTime()-conversionStart;
            Map<String,Object> manifest=new LinkedHashMap<>();
            manifest.put("configuration",config); manifest.put("mode",smoke?"SMOKE_ONLY_NOT_SIFT1M_PERFORMANCE":"FULL_SIFT1M");
            manifest.put("effectiveBaseCount",base.count()); manifest.put("effectiveQueryCount",queryCount);
            manifest.put("queryOrder",order); manifest.put("queryShuffleAlgorithm","java.util.Random(seed), descending Fisher-Yates");
            manifest.put("datasetSha256",expected); manifest.put("runtime",runtime); manifest.put("projectGitCommit",gitCommit(root));
            manifest.put("buildRandomness","Released builder new Random(0), concurrent scheduling not seeded; no public seed setter; not bitwise reproducible.");
            manifest.put("builderThreadPools",Map.of("simd",16,"parallel",16)); manifest.put("protectedW1Sha256",protectedFiles);
            manifest.put("tieAwareDistanceTolerance",0.0); manifest.put("tieAwareReason","SIFT integer coordinates have exactly representable squared L2 sums in double");
            manifest.put("timing","Graph search excludes preparation/conversion and all double distance validation. QPS uses whole timed batch including preparation and result copies, excludes warmup, I/O, validation.");
            manifest.put("resultCsvPolicy","Neighbors: first repeat, one thread, all queries and candidateKs. Latency/stats/summary: every repeat and thread count. repeat_id is 1-based.");
            manifest.put("warmupQueriesPerMeasuredRound",smoke?100:1000);
            Artifacts.json(output.resolve("build-manifest.json"),manifest);
            List<Map<String,Object>> summaries=new ArrayList<>(); Map<Integer,Batch> originalSubsets=new LinkedHashMap<>();
            List<Map<String,Object>> crossChecks=new ArrayList<>();
            List<Integer> candidates=new ArrayList<>(smoke?List.of(10,80,320):config.candidateKs());
            var built=new JVectorIndexFactory().build(store,config.build(),monitor);
            WeakReference<Object> firstGraph=new WeakReference<>(built.graph());
            Map<String,Object> firstBuild=new LinkedHashMap<>(built.performance());
            firstBuild.put("dataReadNanos",loadNanos); firstBuild.put("vectorConversionAndMappingValidationNanos",conversionNanos);
            firstBuild.put("structure",built.structure()); firstBuild.put("originalFloatPayloadBytes",(long)base.count()*128*4);
            firstBuild.put("jvectorFloatPayloadBytes",store.floatPayloadBytes()); firstBuild.put("jvectorLibraryVectorBytes",store.libraryVectorBytes());
            firstBuild.put("heapAfterBuildBytes",JVectorMetrics.heapUsed()); firstBuild.put("windowsProcessMemoryAfterBuild",processMemory());
            firstBuild.put("indexBuildTotalIncludingDataAndConversionNanos",loadNanos+conversionNanos+((Number)built.performance().get("builderTotalNanos")).longValue());
            Artifacts.json(output.resolve("build-performance.json"),firstBuild);
            System.out.println("Built "+base.count()+" nodes; graph bytes (library estimate)="+built.graph().ramBytesUsed());
            try(var exporter=new JVectorResultExporter(output)) {
                for(int candidateIndex=0;candidateIndex<candidates.size();candidateIndex++) {
                    int candidateK=candidates.get(candidateIndex); double currentRecall=0;
                    for(int threads:config.threadCounts()) for(int repeat=1;repeat<=(smoke?1:config.repeats());repeat++) {
                        Batch batch=measure(base,queries,store,built.graph(),order,candidateK,smoke?100:config.warmupQueries(),threads);
                        var summary=exporter.exportBatch(base,queries,official,order,candidateK,repeat,threads,batch,threads==1&&repeat==1);
                        summaries.add(summary); currentRecall=((Number)summary.get("recall")).doubleValue();
                        if(threads==1&&repeat==1) {
                            int n=Math.min(1000,order.length);
                            originalSubsets.put(candidateK,new Batch(Arrays.copyOf(batch.outcomes(),n),0));
                            for(int pos=0;pos<order.length;pos++) if(w1Truth.containsKey(order[pos])) {
                                int qid=order[pos]; var ev=JVectorMetrics.evaluate(base,queries.vector(qid),batch.outcomes()[pos],w1Truth.get(qid),10);
                                crossChecks.add(Map.of("queryId",qid,"candidateK",candidateK,"strictRecallVsW1",ev.strictRecall(),"tieAwareRecallVsW1",ev.tieAwareRecall()));
                            }
                        }
                        System.out.println("candidateK="+candidateK+" threads="+threads+" repeat="+repeat+" recall="+summary.get("recall")+" graphP99ms="+summary.get("p99Ms"));
                    }
                    if(!smoke && candidateIndex==candidates.size()-1 && currentRecall<0.99) {
                        Optional<Integer> next=config.extensionCandidateKs().stream().filter(k->!candidates.contains(k)).findFirst();
                        if(next.isPresent()) {
                            candidates.add(next.get());
                            manifest.put("extensionReason","Largest completed candidateK strict Recall remains below 0.99; extend only from frozen extension grid");
                            manifest.put("effectiveCandidateKs",List.copyOf(candidates)); Artifacts.json(output.resolve("build-manifest.json"),manifest);
                        }
                    }
                }
            } finally { built.close(); built=null; }
            Artifacts.json(output.resolve("recall-validation.json"),Map.of("allResultsValidated",true,"tieTolerance",0.0,"w1CrossChecks",crossChecks,
                    "summaryRows",summaries,"resultsNeighborPolicy","first repeat, one thread","idMappingSamples",1000));
            if(!smoke) {
                for(int attempt=0;attempt<5 && firstGraph.get()!=null;attempt++) { System.gc(); Thread.sleep(200); }
                if(firstGraph.get()!=null) throw new IllegalStateException("First graph still reachable; refuse overlapping full builds");
                if(JVectorRuntimeInfo.osBean().getFreeMemorySize()<1024L*1024*1024) throw new IllegalStateException("Insufficient headroom for second build");
                int[] subset=Arrays.copyOf(order,1000);
                List<Map<String,Object>> comparison=new ArrayList<>();
                try(var second=new JVectorIndexFactory().build(store,config.build(),monitor);
                    var rebuildExporter=new JVectorResultExporter(output.resolve("rebuild"))) {
                    Artifacts.json(output.resolve("second-build-performance.json"),Map.of("performance",second.performance(),"structure",second.structure()));
                    for(int candidateK:candidates) {
                        Batch batch=measure(base,queries,store,second.graph(),subset,candidateK,1000,1);
                        rebuildExporter.exportBatch(base,queries,official,subset,candidateK,1,1,batch,true);
                        Map<String,Object> oldStats=subsetStats(base,queries,official,subset,originalSubsets.get(candidateK));
                        Map<String,Object> newStats=subsetStats(base,queries,official,subset,batch);
                        comparison.add(Map.of("candidateK",candidateK,"firstBuild",oldStats,"secondBuild",newStats));
                        System.out.println("Independent rebuild candidateK="+candidateK+" first="+oldStats+" second="+newStats);
                    }
                }
                Artifacts.json(output.resolve("reproducibility.json"),Map.of("secondBuildComplete",true,"firstGraphCollectedBeforeRebuild",true,
                        "queryIds",subset,"comparisons",comparison,"bitwiseGraphReproducibilityClaimed",false,
                        "policy","Compare same fixed 1000 queries and candidate grid; first subset from initial single-thread round; second measured after 1000 warmups."));
            } else Artifacts.json(output.resolve("reproducibility.json"),Map.of("secondBuildComplete",false,"reason","Smoke only; formal rebuild not represented by smoke"));
            Artifacts.json(output.resolve("memory-report.json"),monitor.report());
            if(!protectedFiles.equals(protectW1(root))) throw new IllegalStateException("W1 protected files changed");
            Artifacts.json(output.resolve("w1-protection.json"),Map.of("unchanged",true,"sha256",protectedFiles));
            Artifacts.json(output.resolve("run-total.json"),Map.of("elapsedNanos",System.nanoTime()-totalStart));
        }
    }
    public static int[] queryOrder(int count,long seed) {
        int[] ids=IntStream.range(0,count).toArray(); Random random=new Random(seed);
        for(int i=count-1;i>0;i--) { int j=random.nextInt(i+1), v=ids[i]; ids[i]=ids[j]; ids[j]=v; } return ids;
    }
    private static Map<String,Object> subsetStats(Dataset base,Dataset queries,int[][] truth,int[] order,Batch batch) {
        double strict=0,tie=0,visited=0,expanded=0,baseExpanded=0; long[] latency=new long[order.length];
        for(int pos=0;pos<order.length;pos++) {
            var r=batch.outcomes()[pos]; var e=JVectorMetrics.evaluate(base,queries.vector(order[pos]),r,truth[order[pos]],10);
            strict+=e.strictRecall(); tie+=e.tieAwareRecall(); visited+=r.visitedCount(); expanded+=r.expandedCount(); baseExpanded+=r.expandedBaseLayer(); latency[pos]=r.graphSearchNanos();
        }
        return Map.of("recall",strict/order.length,"tieAwareRecall",tie/order.length,"p99Ms",JVectorMetrics.nearestRank(latency,.99)/1e6,
                "averageVisited",visited/order.length,"averageExpanded",expanded/order.length,"averageExpandedBaseLayer",baseExpanded/order.length);
    }
    private static Map<Integer,int[]> w1Reference(Path root,Dataset base,Dataset queries,Path output) throws Exception {
        var manifest=Artifacts.JSON.readTree(root.resolve("results/w1/generated/run-manifest.json").toFile());
        Map<Integer,int[]> result=new TreeMap<>(); GroundTruthGenerator exact=new GroundTruthGenerator(base);
        for(var id:manifest.get("queryIds")) result.put(id.intValue(),exact.global(queries.vector(id.intValue()),10).neighbors().stream().mapToInt(Neighbor::vectorId).toArray());
        Artifacts.json(output.resolve("w1-exact-reference.json"),result); return result;
    }
    private static Map<String,String> protectW1(Path root) throws Exception {
        Map<String,String> result=new TreeMap<>();
        for(String directory:List.of("bench-core/src","data/processed/metadata","data/processed/ground-truth/w1","results/w1/generated")) {
            try(var paths=Files.walk(root.resolve(directory))) {
                for(Path file:paths.filter(Files::isRegularFile).toList()) result.put(root.relativize(file).toString().replace('\\','/'),Artifacts.sha256(file));
            }
        }
        result.put("docs/w1-report.md",Artifacts.sha256(root.resolve("docs/w1-report.md"))); return result;
    }
    private static String gitCommit(Path root) throws Exception {
        Process p=new ProcessBuilder("git","-C",root.toString(),"rev-parse","HEAD").redirectErrorStream(true).start();
        String hash=new String(p.getInputStream().readAllBytes()).trim(); if(p.waitFor()!=0) throw new IllegalStateException("Git commit unavailable"); return hash;
    }
    private static Object processMemory() {
        try {
            long pid=ProcessHandle.current().pid();
            Process p=new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-Command",
                    "Get-Process -Id "+pid+" | Select-Object WorkingSet64,PrivateMemorySize64 | ConvertTo-Json -Compress").start();
            if(!p.waitFor(10,TimeUnit.SECONDS)) { p.destroyForcibly(); return Map.of("available",false,"reason","process memory probe timeout"); }
            if(p.exitValue()!=0) return Map.of("available",false,"reason","process memory probe failed");
            return Artifacts.JSON.readTree(p.getInputStream().readAllBytes());
        } catch(Exception e) { return Map.of("available",false,"reason",e.toString()); }
    }
}
