package com.filteredann.w3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.filteredann.dataset.*;
import com.filteredann.metadata.*;
import com.filteredann.filter.FilterBitmap;
import com.filteredann.index.SearchResult.Neighbor;
import com.filteredann.jvector.*;
import com.filteredann.util.Artifacts;
import java.io.*;
import java.nio.file.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Scoped project experiment. This runner does not implement the original full W3 matrix. */
public final class W3MinimalRunner {
    record Inputs(Dataset base,Dataset queries,MetadataStore metadata,int[] queryIds) { }
    record Cell(String id,String cache,String filter,W3Strategies.Strategy strategy,int budget,int repeat,int threads) { }
    record Batch(W3Strategies.Outcome[] outcomes,long wallNanos) { }
    record Summary(String cellId,String cache,String filter,String strategy,int budget,int repeat,int threads,int queries,
        long wallNanos,double strict,double tie,double insufficientRate,double zeroRate,double meanNanos,long p50Nanos,long p95Nanos,long p99Nanos,
        long coreP99Nanos,double qps,double visited,double expanded,double expandedBase,long gcCount,long gcMillis) { }
    static final ObjectMapper YAML=new ObjectMapper(new YAMLFactory());
    static int[] integers(JsonNode array) { int[] a=new int[array.size()]; for(int i=0;i<a.length;i++) a[i]=array.get(i).asInt(); return a; }
    static Inputs inputs(Path root,JsonNode config) throws Exception {
        int n=config.get("baseCount").asInt(),q=config.get("queryCount").asInt();
        if(n!=100000 || q!=100 || config.get("k").asInt()!=10) throw new IllegalArgumentException("Runner frozen to minimal-v1 100k/100q/K10");
        Dataset base=W3Smoke.prefix(root.resolve("data/raw/sift_base.fvecs"),n,128);
        Dataset allQueries=new SiftDatasetReader().readVectors(root.resolve("data/raw/sift_query.fvecs"),10000,128);
        JsonNode old=Artifacts.JSON.readTree(root.resolve("results/w2/generated/build-manifest.json").toFile());
        int[] ids=Arrays.copyOf(integers(old.get("queryOrder")),q); float[] values=new float[q*128];
        for(int i=0;i<q;i++) System.arraycopy(allQueries.vector(ids[i]),0,values,i*128,128);
        Path meta=root.resolve("data/processed/metadata/sift1m-random-v1.bin");
        if(!Artifacts.sha256(meta).equals("1591e21cd325201615f87d0092d266d04f95f3ff07f1c18642038f26eab2d44c")) throw new IOException("Frozen metadata mismatch");
        return new Inputs(base,new Dataset(values,128),W3Smoke.projectedMetadata(new MetadataGenerator().read(meta,20261113),n),ids);
    }
    static List<W3Workload.Filter> filters(Inputs in,W3Workload.Labels labels,JsonNode config) {
        List<String> names=new ArrayList<>(); config.get("filters").forEach(n->names.add(n.asText()));
        var available=W3Workload.filters(in.metadata,labels); List<W3Workload.Filter> selected=new ArrayList<>();
        for(String name:names) selected.add(available.stream().filter(f->f.id().equals(name)).findFirst().orElseThrow());
        if(selected.size()!=6) throw new IllegalArgumentException("Six preregistered filters required");
        return selected;
    }
    static void prepare(Path root,Path out,JsonNode config) throws Exception {
        Files.createDirectory(out.resolve("prepared")); Path directory=out.resolve("prepared");
        Inputs in=inputs(root,config); long start=System.nanoTime(); var labels=W3Workload.labels(in.base);
        long tagNanos=System.nanoTime()-start; W3Workload.writeLabels(directory.resolve("labels.bin"),labels);
        var filters=filters(in,labels,config);
        Artifacts.json(directory.resolve("inputs.json"),Map.of("baseCount",in.base.count(),"queryIds",in.queryIds,"randomRanks",in.metadata.filterRanks(),
            "categories",in.metadata.categories(),"anchors",labels.anchors(),"clusterRanks",labels.rank(),"clusterIds",labels.cluster(),"clusterOrder",labels.clusterOrder()));
        Artifacts.json(directory.resolve("filters.json"),filters.stream().map(W3Workload.Filter::manifest).toList());
        List<Map<String,Object>> shards=new ArrayList<>();
        for(var filter:filters) { shards.add(W3GroundTruthExport.write(directory.resolve("gt"),in.base,in.queries,filter,in.queryIds)); System.out.println("GT complete: "+filter.id()); }
        Artifacts.json(directory.resolve("manifest.json"),Map.of("scope","w3-minimal-v1","status","prepared-awaiting-independent-validation","gtGroups",600,
            "tagNanos",tagNanos,"labelSha256",Artifacts.sha256(directory.resolve("labels.bin")),"inputsSha256",Artifacts.sha256(directory.resolve("inputs.json")),
            "filtersSha256",Artifacts.sha256(directory.resolve("filters.json")),"configSha256",Artifacts.sha256(root.resolve("config/w3-minimal.yaml")),"shards",shards));
    }
    static List<List<Neighbor>> truth(Path directory,W3Workload.Filter filter,int[] ids) throws IOException {
        Map<Integer,Integer> position=new HashMap<>(); List<List<Neighbor>> truth=new ArrayList<>();
        for(int i=0;i<ids.length;i++) { position.put(ids[i],i); truth.add(new ArrayList<>()); }
        try(var lines=Files.lines(directory.resolve("gt/"+filter.id()+".csv"))) {
            lines.skip(1).forEach(line->{ var a=line.split(","); int q=position.get(Integer.parseInt(a[1]));
                if(Integer.parseInt(a[2])!=truth.get(q).size()) throw new IllegalStateException("GT order");
                truth.get(q).add(new Neighbor(Integer.parseInt(a[3]),Double.parseDouble(a[4])));
            });
        }
        for(var row:truth) if(row.size()!=Math.min(10,filter.bitmap().cardinality())) throw new IOException("Incomplete GT");
        return truth;
    }
    static Batch batch(Inputs in,JVectorVectorStore store,JVectorIndexFactory.BuiltIndex graph,Cell cell,Supplier<FilterBitmap> acquire,int warmup) throws Exception {
        W3Strategies.Outcome[] outcomes=new W3Strategies.Outcome[in.queries.count()];
        CountDownLatch ready=new CountDownLatch(cell.threads),go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(cell.threads)) {
            List<Future<?>> futures=new ArrayList<>();
            for(int thread=0;thread<cell.threads;thread++) {
                final int workerId=thread;
                futures.add(pool.submit(()->{
                    boolean signaled=false;
                    try(var worker=new W3Strategies(in.base,store,graph.graph())) {
                        for(int i=workerId;i<warmup;i+=cell.threads) worker.search(in.queries,i%in.queries.count(),acquire,cell.strategy,cell.budget,Math.max(10,cell.budget));
                        ready.countDown(); signaled=true; go.await();
                        for(int q=workerId;q<in.queries.count();q+=cell.threads) outcomes[q]=worker.search(in.queries,q,acquire,cell.strategy,cell.budget,Math.max(10,cell.budget));
                    } catch(Exception e) { throw new CompletionException(e); }
                    finally { if(!signaled) ready.countDown(); }
                }));
            }
            if(!ready.await(30,TimeUnit.MINUTES)) throw new TimeoutException("Warmup timeout");
            long start=System.nanoTime(); go.countDown();
            for(var future:futures) future.get(30,TimeUnit.MINUTES);
            return new Batch(outcomes,System.nanoTime()-start);
        }
    }
    static long[] gc() {
        long count=0,millis=0; for(var bean:ManagementFactory.getGarbageCollectorMXBeans()) { count+=Math.max(0,bean.getCollectionCount()); millis+=Math.max(0,bean.getCollectionTime()); }
        return new long[]{count,millis};
    }
    static Summary export(Path root,Path out,String buildId,Inputs in,Cell cell,W3Workload.Filter filter,List<List<Neighbor>> truth,Batch batch,long[] before,long[] after) throws IOException {
        int n=in.queries.count(),shortCount=0,zeroCount=0; double strict=0,tie=0,sum=0,visited=0,expanded=0,expandedBase=0;
        long[] times=new long[n],core=new long[n]; Path dir=out.resolve("cells"); Files.createDirectories(dir);
        try(var requests=Files.newBufferedWriter(dir.resolve(cell.id+"-requests.csv"),StandardOpenOption.CREATE_NEW);
            var results=Files.newBufferedWriter(dir.resolve(cell.id+"-results.csv"),StandardOpenOption.CREATE_NEW)) {
            requests.write("run_id,build_id,cell_id,cache,filter_id,strategy,candidate_k,rerank_k,limit,repeat_id,thread_count,query_id,query_ns,score_ns,filter_ns,core_ns,conversion_ns,post_ns,e2e_ns,result_count,candidate_count,visited,expanded,expanded_base,reranked,strict,tie,fixed_k,fulfillment,missing,zero\n");
            results.write("run_id,build_id,cell_id,cache,filter_id,strategy,candidate_k,repeat_id,thread_count,query_id,rank,vector_id,score,exact_squared_l2,strict_hit,tie_hit,boundary\n");
            for(int q=0;q<n;q++) {
                var o=Objects.requireNonNull(batch.outcomes[q]); var quality=W3Validation.validate(in.base,in.queries.vector(q),filter.bitmap(),o.ids(),truth.get(q));
                if(cell.strategy==W3Strategies.Strategy.E && quality.strict()!=1) throw new IllegalStateException("Exact strict recall != 1");
                for(int i=0;i<o.scores().length;i++) if(cell.strategy!=W3Strategies.Strategy.E && (!Float.isFinite(o.scores()[i]) || (i>0 && o.scores()[i]>o.scores()[i-1]))) throw new IllegalStateException("Score direction/finite check");
                strict+=quality.strict(); tie+=quality.tieAware(); sum+=o.endToEnd(); times[q]=o.endToEnd(); core[q]=o.coreSearch();
                visited+=o.visited(); expanded+=o.expanded(); expandedBase+=o.expandedBase(); if(quality.missing()>0) shortCount++; if(o.ids().length==0) zeroCount++;
                String prefix=out.getFileName()+","+buildId+","+cell.id+","+cell.cache+","+filter.id()+","+cell.strategy+","+cell.budget;
                requests.write(prefix+","+(cell.strategy==W3Strategies.Strategy.E?"":cell.budget)+","+(cell.strategy==W3Strategies.Strategy.O?cell.budget:10)+","+cell.repeat+","+cell.threads+","+in.queryIds[q]+","+o.queryPreparation()+","+o.scorePreparation()+","+o.filterAcquisition()+","+o.coreSearch()+","+o.conversion()+","+o.postFilter()+","+o.endToEnd()+","+o.ids().length+","+o.candidateCount()+","+o.visited()+","+o.expanded()+","+o.expandedBase()+","+o.reranked()+","+quality.strict()+","+quality.tieAware()+","+quality.fixedK()+","+quality.fulfillment()+","+quality.missing()+","+(o.ids().length==0?1:0)+"\n");
                for(int rank=0;rank<o.ids().length;rank++) results.write(prefix+","+cell.repeat+","+cell.threads+","+in.queryIds[q]+","+rank+","+o.ids()[rank]+","+o.scores()[rank]+","+quality.distances()[rank]+","+quality.strictHits()[rank]+","+quality.tieHits()[rank]+","+quality.boundary()+"\n");
            }
        }
        return new Summary(cell.id,cell.cache,cell.filter,cell.strategy.toString(),cell.budget,cell.repeat,cell.threads,n,batch.wallNanos,strict/n,tie/n,(double)shortCount/n,(double)zeroCount/n,sum/n,
            W3Validation.quantile(times,.5),W3Validation.quantile(times,.95),W3Validation.quantile(times,.99),W3Validation.quantile(core,.99),n*1e9/batch.wallNanos,visited/n,expanded/n,expandedBase/n,after[0]-before[0],after[1]-before[1]);
    }
    static void bitmaps(Path out,Inputs in,List<W3Workload.Filter> filters) throws Exception {
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        if(bean.isThreadAllocatedMemorySupported() && !bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        List<Map<String,Object>> rows=new ArrayList<>();
        for(var filter:filters) {
            var left=FilterBitmap.build(in.metadata,filter.left()); var right=FilterBitmap.build(in.metadata,filter.right());
            for(String mode:List.of("single-left","double-direct","clone-and")) for(int repeat=0;repeat<10;repeat++) {
                long allocated=bean.isThreadAllocatedMemoryEnabled()?bean.getThreadAllocatedBytes(Thread.currentThread().threadId()):-1; long[] before=gc();
                long start=System.nanoTime(); var bitmap=switch(mode) { case "single-left"->FilterBitmap.build(in.metadata,filter.left()); case "double-direct"->filter.build(in.metadata); default->left.and(right); };
                long nanos=System.nanoTime()-start; long endAllocated=bean.isThreadAllocatedMemoryEnabled()?bean.getThreadAllocatedBytes(Thread.currentThread().threadId()):-1;
                long[] after=gc();
                if(!mode.equals("single-left") && !W3Workload.bitmapHash(bitmap).equals(W3Workload.bitmapHash(filter.bitmap()))) throw new AssertionError("Bitmap mismatch");
                rows.add(Map.of("filter",filter.id(),"mode",mode,"repeat",repeat,"nanos",nanos,"allocatedBytes",allocated<0?-1:endAllocated-allocated,
                    "matches",bitmap.cardinality(),"gcCount",after[0]-before[0],"gcMillis",after[1]-before[1],"theoreticalPayloadBytes",(in.base.count()+7)/8));
            }
        }
        Artifacts.json(out.resolve("bitmap-cost.json"),rows);
    }
    static int selected(List<Summary> rows,String filter,W3Strategies.Strategy strategy,int[] budgets) {
        if(strategy==W3Strategies.Strategy.E) return 0;
        double best=Double.POSITIVE_INFINITY; int selected=budgets[budgets.length-1];
        for(int budget:budgets) {
            var group=rows.stream().filter(s->s.cache.equals("reuse") && s.filter.equals(filter) && s.strategy.equals(strategy.toString()) && s.threads==1 && s.budget==budget).toList();
            if(group.size()!=3 || group.stream().anyMatch(s->s.strict<.95)) continue;
            long[] times=group.stream().mapToLong(Summary::p99Nanos).toArray(); double median=W3Validation.quantile(times,.5);
            if(median<best) { best=median; selected=budget; }
        }
        return selected;
    }
    static void benchmark(Path root,Path out,JsonNode config) throws Exception {
        if(!Artifacts.JSON.readTree(out.resolve("prepared/independent-validation.json").toFile()).get("passed").asBoolean()) throw new IOException("Independent GT validation required");
        JsonNode prepared=Artifacts.JSON.readTree(out.resolve("prepared/manifest.json").toFile());
        if(!Artifacts.sha256(root.resolve("config/w3-minimal.yaml")).equals(prepared.get("configSha256").asText())) throw new IOException("Configuration changed after GT");
        if(!Artifacts.sha256(out.resolve("prepared/labels.bin")).equals(prepared.get("labelSha256").asText())) throw new IOException("Labels changed after GT");
        Inputs in=inputs(root,config); var labels=W3Workload.readLabels(out.resolve("prepared/labels.bin"),in.base.count()); var filters=filters(in,labels,config);
        Map<String,List<List<Neighbor>>> truth=new HashMap<>();
        for(var filter:filters) {
            var shard=prepared.get("shards"); String expected=null; for(var s:shard) if(s.get("filterId").asText().equals(filter.id())) expected=s.get("sha256").asText();
            if(!Artifacts.sha256(out.resolve("prepared/gt/"+filter.id()+".csv")).equals(expected)) throw new IOException("GT hash changed");
            truth.put(filter.id(),truth(out.resolve("prepared"),filter,in.queryIds));
        }
        var store=new JVectorVectorStore(in.base); store.verifyMapping(in.base,1000,20261114); String buildId=out.getFileName()+"-graph1";
        long start=System.nanoTime();
        try(var graph=new JVectorIndexFactory().build(store,JVectorIndexFactory.BuildParameters.baseline(),null)) {
            Artifacts.json(out.resolve("build.json"),Map.of("buildId",buildId,"performance",graph.performance(),"structure",graph.structure(),"wallNanos",System.nanoTime()-start,
                "originalFloatBytes",100000L*128*4,"convertedVectorLibraryBytes",store.libraryVectorBytes()));
            bitmaps(out,in,filters); int[] budgets=integers(config.get("candidateKs")); List<Cell> blocks=new ArrayList<>();
            for(var filter:filters) for(int threads:integers(config.get("threadCounts"))) for(var strategy:W3Strategies.Strategy.values())
                for(int budget:strategy==W3Strategies.Strategy.E?new int[]{0}:budgets) blocks.add(new Cell("","reuse",filter.id(),strategy,budget,0,threads));
            Collections.shuffle(blocks,new Random(config.get("blockScheduleSeed").asLong())); List<Cell> schedule=new ArrayList<>();
            for(int round=0;round<3;round++) for(int i=0;i<blocks.size();i++) { var b=blocks.get((i+round*37)%blocks.size()); schedule.add(new Cell(String.format("hot-%03d",schedule.size()),"reuse",b.filter,b.strategy,b.budget,round,b.threads)); }
            Artifacts.json(out.resolve("schedule.json"),schedule); List<Summary> summaries=new ArrayList<>();
            for(var cell:schedule) {
                var filter=filters.stream().filter(f->f.id().equals(cell.filter)).findFirst().orElseThrow(); long[] before=gc();
                Batch batch=batch(in,store,graph,cell,filter::bitmap,config.get("warmupQueries").asInt()); long[] after=gc();
                Summary summary=export(root,out,buildId,in,cell,filter,truth.get(filter.id()),batch,before,after); summaries.add(summary);
                Artifacts.json(out.resolve("summary.json"),summaries); Artifacts.json(out.resolve("progress.json"),Map.of("status","running","completedHotCells",summaries.size(),"expectedHotCells",360));
                System.out.println("Completed "+cell.id+" "+cell.filter+" "+cell.strategy+" B="+cell.budget+" threads="+cell.threads);
            }
            List<Map<String,Object>> points=new ArrayList<>(); int online=0;
            for(var filter:filters) for(var strategy:W3Strategies.Strategy.values()) {
                int budget=selected(summaries,filter.id(),strategy,budgets);
                boolean achieved=summaries.stream().filter(s->s.cache.equals("reuse") && s.filter.equals(filter.id()) && s.strategy.equals(strategy.toString()) && s.threads==1 && s.budget==budget).allMatch(s->s.strict>=.95);
                points.add(Map.of("filter",filter.id(),"strategy",strategy,"budget",budget,"target",.95,"achievedAllThree",achieved));
                for(int repeat=0;repeat<3;repeat++) {
                    var cell=new Cell(String.format("online-%03d",online++),"online",filter.id(),strategy,budget,repeat,1); long[] before=gc();
                    Batch batch=batch(in,store,graph,cell,()->filter.build(in.metadata),20); long[] after=gc();
                    summaries.add(export(root,out,buildId,in,cell,filter,truth.get(filter.id()),batch,before,after));
                    Artifacts.json(out.resolve("summary.json"),summaries); System.out.println("Completed "+cell.id+" "+filter.id()+" "+strategy);
                }
            }
            Artifacts.json(out.resolve("quality-points.json"),points);
            Artifacts.json(out.resolve("progress.json"),Map.of("status","measured-awaiting-independent-validation","completedHotCells",360,"completedOnlineCells",72,"scope","w3-minimal-v1"));
        }
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]),out=Path.of(args[1]); String mode=args[2]; W3Telemetry.start();
        JsonNode config=YAML.readTree(root.resolve("config/w3-minimal.yaml").toFile());
        Artifacts.json(out.resolve(mode+"-runtime.json"),JVectorRuntimeInfo.capture(root.resolve(".tools/jvector-4.0.2/jvector-4.0.2-sources.jar")));
        if(mode.equals("prepare")) prepare(root,out,config); else if(mode.equals("benchmark")) benchmark(root,out,config); else throw new IllegalArgumentException("Unknown mode");
    }
}
