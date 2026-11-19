// SPDX-License-Identifier: Apache-2.0
package com.filteredann.planner.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filteredann.planner.api.*;
import com.filteredann.planner.catalog.PerformanceCatalog.Point;
import com.filteredann.planner.engine.FourStrategyEngine.Strategy;
import com.filteredann.planner.filter.FilterSpec;
import com.filteredann.index.SearchIndex;
import com.filteredann.util.Artifacts;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.lang.management.ManagementFactory;

/** Product request-chain comparison. One live graph across both phases, no timed GT IO. */
public final class PerformanceBenchmark {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static volatile String phase="startup";
    private static volatile int progress;
    private static volatile boolean finished;
    private record Work(int filter,int query) { }
    private static double median(double[] values){Arrays.sort(values);return values[values.length/2];}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath(),out=Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(out);
        try {
            run(root,out);
        } catch(Throwable e) {
            JSON.writeValue(out.resolve("state.json").toFile(),Map.of("state","INCOMPLETE","phase",phase,"progress",progress,"error",e.toString()));
            throw e;
        } finally {finished=true;}
    }
    private static void run(Path root,Path out)throws Exception {
        JSON.writeValue(out.resolve("state.json").toFile(),Map.of("state","RUNNING"));
        startHeapSampler(out);
        Path split=root.resolve("results/w3-r-ef-final002/postprocess-20261119T050558/engineering-20261119T002913/measured/query-split.json");
        var splitDoc=JSON.readTree(split.toFile());
        Set<Integer> tuning=new HashSet<>();for(var id:splitDoc.required("tuning"))tuning.add(id.asInt());
        int[] queryIds=new int[1000];for(int i=0;i<1000;i++){queryIds[i]=splitDoc.required("holdout").get(i).asInt();if(tuning.contains(queryIds[i]))throw new IllegalStateException("Tuning leakage");}
        if(Arrays.stream(queryIds).distinct().count()!=1000)throw new IllegalStateException("Duplicate query");
        try(var runtime=PlannerRuntime.open(root,250000,false)) {
            var evidence=new ArrayList<>(runtime.catalog().filters());
            int nf=evidence.size();float[][] queries=new float[1000][];for(int i=0;i<1000;i++)queries[i]=runtime.queryVector(queryIds[i]);
            GroundTruth[][] truths=new GroundTruth[nf][1000];QueryRequest[][] requests=new QueryRequest[nf][1000];
            int[][] budgets=new int[nf][4];List<Map<String,Object>> choices=new ArrayList<>();List<Map<String,Object>> cold=new ArrayList<>();
            for(int f=0;f<nf;f++) {
                var ev=evidence.get(f);var filter=ev.spec();var bitmap=runtime.prepare(filter);
                if(!bitmap.bitmapSha256().equals(ev.bitmapSha256()))throw new IllegalStateException("Bitmap identity");
                cold.add(Map.of("filter",ev.id(),"matches",bitmap.matches(),"cold_acquisition_ns",bitmap.acquisitionNanos()));
                for(Strategy s:Strategy.values()) {
                    List<Point> points=ev.tuning().stream().filter(p->p.strategy()==s).toList();
                    List<Point> eligible=points.stream().filter(p->p.strictMin()>=.95).toList();
                    Point selected=(eligible.isEmpty()?points:eligible).stream().min((a,b)->{
                        if(eligible.isEmpty()){int q=Double.compare(b.strictMin(),a.strictMin());if(q!=0)return q;}
                        int c=Double.compare(a.e2eMeanMedianMs(),b.e2eMeanMedianMs());return c!=0?c:Integer.compare(a.budget(),b.budget());
                    }).orElseThrow();
                    budgets[f][s.ordinal()]=selected.budget();choices.add(Map.of("filter",ev.id(),"strategy",s.name(),"budget",selected.budget(),"tuning_strict_min",selected.strictMin(),"tuning_target_met",!eligible.isEmpty()));
                }
                for(int q=0;q<1000;q++) {
                    requests[f][q]=new QueryRequest(queries[q],filter,.95,null,false);
                    truths[f][q]=Objects.requireNonNull(runtime.truth(filter,queryIds[q],queries[q]),"Compatible engineering GT required");
                    var gt=truths[f][q];var ids=gt.ids();var distances=gt.squaredDistances();
                    if(!gt.bitmapSha256().equals(ev.bitmapSha256())||!gt.datasetId().equals(runtime.context().datasetId())||ids.length!=10)throw new IllegalStateException("GT identity");
                    for(int rank=0;rank<10;rank++) {
                        if(!bitmap.bitmap().get(ids[rank]) || Double.compare(runtime.vectorStore().squaredL2(queries[q],ids[rank]),distances[rank])!=0)throw new IllegalStateException("GT distance");
                        if(rank>0&&(distances[rank]<distances[rank-1]||distances[rank]==distances[rank-1]&&ids[rank]<ids[rank-1]))throw new IllegalStateException("GT sort");
                    }
                }
            }
            JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("budget-selection.json").toFile(),choices);
            JSON.writeValue(out.resolve("cold-bitmap-cost.json").toFile(),cold);
            var manifest=new LinkedHashMap<String,Object>();
            manifest.put("build_id",runtime.context().currentBuildId());manifest.put("nodes",250000);manifest.put("dimensions",128);manifest.put("query_ids",queryIds);
            manifest.put("query_split_sha256",Artifacts.sha256(split));manifest.put("dataset",runtime.catalog().dataset());
            manifest.put("rounds",3);manifest.put("filters",evidence.stream().map(e->e.id()).toList());manifest.put("warmup_per_filter_per_method",50);
            manifest.put("workload","equal-weight mixed 8 filters x 1000 Holdout queries; Fisher-Yates Java seed 20261119+round");
            manifest.put("quality_guard","GT absent inside measured API call; no oracle fallback. Quality verified outside timing. Unknown Actual Recall in API is preserved.");
            manifest.put("timing","outer full API call nanos; searchNanos separate; batch wall excludes GT verification/CSV IO");
            manifest.put("jvm",ManagementFactory.getRuntimeMXBean().getInputArguments());
            JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("manifest.json").toFile(),manifest);
            runPhase("before",runtime,null,requests,truths,queries,queryIds,evidence.stream().map(e->e.id()).toList(),budgets,out);
            phase="awaiting-reviewed-optimization";progress=0;
            Files.writeString(out.resolve("baseline-complete"),runtime.context().currentBuildId());
            System.out.println("BASELINE_COMPLETE graph retained; waiting for reviewed optimization class and continue-after marker");System.out.flush();
            while(!Files.exists(out.resolve("continue-after")))Thread.sleep(1000);
            String className=Files.readString(out.resolve("continue-after")).strip();
            var kernel=(SearchIndex)Class.forName(className).getConstructor(com.filteredann.planner.engine.RowsVectorStore.class).newInstance(runtime.vectorStore());
            // Exact equality check on this graph's vectors/bitmaps before optimized timings.
            try(var original=runtime.newSession();var optimized=runtime.newSession()) {
                original.exactKernel(new com.filteredann.planner.engine.ProductExactIndex(runtime.vectorStore()));
                optimized.exactKernel(kernel);
                int checked=0;
                for(int f=0;f<nf;f++)for(int q=0;q<1000;q++) {
                    var a=original.queryFixed(requests[f][q],Strategy.E,0);var b=optimized.queryFixed(requests[f][q],Strategy.E,0);
                    if(!a.resultVectorIds().equals(b.resultVectorIds()))throw new IllegalStateException("New exact kernel result mismatch");checked++;
                }
                JSON.writeValue(out.resolve("same-input-consistency.json").toFile(),Map.of("checked",checked,"mismatches",0,"gt_tolerance",0));
            }
            runPhase("after",runtime,kernel,requests,truths,queries,queryIds,evidence.stream().map(e->e.id()).toList(),budgets,out);
            phase="complete";
            JSON.writeValue(out.resolve("state.json").toFile(),Map.of("state","COMPLETED","build_id",runtime.context().currentBuildId(),"measured_requests",240000));
            System.out.println("BENCHMARK_COMPLETED same graph; 240000 measured requests");
        }
    }
    private static void runPhase(String label,PlannerRuntime runtime,SearchIndex kernel,QueryRequest[][] requests,GroundTruth[][] truths,float[][] queries,int[] queryIds,List<String> filters,int[][] budgets,Path out)throws Exception {
        try(var api=runtime.newSession();var raw=Files.newBufferedWriter(out.resolve(label+"-benchmark-results.csv"));var batches=Files.newBufferedWriter(out.resolve(label+"-batch-wall.csv"))) {
            api.exactKernel(kernel==null?new com.filteredann.planner.engine.ProductExactIndex(runtime.vectorStore()):kernel);
            raw.write("phase,repeat,sequence,method,filter,query_id,budget,executed,request_ns,api_ns,search_ns,bitmap_ns,planning_ns,strict,tie,insufficient,fallback,visited,expanded,result_ids\n");
            batches.write("phase,repeat,method,requests,wall_ns\n");
            phase=label+"-warmup";
            for(int m=0;m<5;m++)for(int f=0;f<8;f++)for(int q=0;q<50;q++)call(api,requests[f][q],m,budgets[f]);
            for(int round=0;round<3;round++) {
                List<Work> work=new ArrayList<>();for(int f=0;f<8;f++)for(int q=0;q<1000;q++)work.add(new Work(f,q));
                Collections.shuffle(work,new Random(20261119L+round));
                for(int position=0;position<5;position++) {
                    int method=(position+round*2)%5;String name=method==4?"Planner":Strategy.values()[method].name();
                    phase=label+"-round"+round+"-"+name;progress=0;
                    System.out.println("BATCH_START "+phase+" requests=8000");System.out.flush();
                    QueryResponse[] values=new QueryResponse[8000];long[] times=new long[8000];
                    long batchStart=System.nanoTime();
                    for(int i=0;i<work.size();i++) {
                        Work w=work.get(i);long start=System.nanoTime();values[i]=call(api,requests[w.filter][w.query],method,budgets[w.filter]);times[i]=System.nanoTime()-start;progress=i+1;
                    }
                    long wall=System.nanoTime()-batchStart;
                    batches.write(label+","+round+","+name+",8000,"+wall+"\n");batches.flush();
                    double quality=0;
                    for(int i=0;i<values.length;i++) {
                        Work w=work.get(i);var r=values[i];var gt=truths[w.filter][w.query];int strict=0,tie=0;
                        Set<Integer> truth=new HashSet<>();for(int id:gt.ids())truth.add(id);double boundary=gt.squaredDistances()[9];
                        for(int id:r.resultVectorIds()) {if(truth.contains(id))strict++;double d=runtime.vectorStore().squaredL2(queries[w.query],id);if(d<=boundary)tie++;}
                        quality+=strict/10.0;
                        if(r.actualRecall()!=null || r.decision().cacheState()!=com.filteredann.planner.filter.FilterEngine.CacheState.HOT)throw new IllegalStateException("GT in measured path or cache mismatch");
                        var attempt=r.attempts().get(0);
                        raw.write(label+","+round+","+i+","+name+","+filters.get(w.filter)+","+queryIds[w.query]+","+r.selectedBudget()+","+r.selectedStrategy()+","+times[i]+","+r.queryLatencyNanos()+","+r.searchNanos()+","+r.bitmapNanos()+","+r.planningNanos()+","+(strict/10.0)+","+(tie/10.0)+","+(r.resultVectorIds().size()<10?1:0)+","+(r.executionFallback()?1:0)+","+attempt.visitedCount()+","+attempt.expandedCount()+",\""+r.resultVectorIds().toString()+"\"\n");
                    }
                    raw.flush();System.out.printf(Locale.ROOT,"BATCH_DONE %s wallSeconds=%.3f strict=%.6f%n",phase,wall/1e9,quality/8000);System.out.flush();
                }
            }
        }
    }
    private static QueryResponse call(QueryApi api,QueryRequest request,int method,int[] budgets) {
        return method==4?api.query(request):api.queryFixed(request,Strategy.values()[method],budgets[method]);
    }
    private static void startHeapSampler(Path out) {
        Thread worker=new Thread(()->{
            try(var writer=Files.newBufferedWriter(out.resolve("heap.csv"))) {
                writer.write("utc,phase,completed,heap_used,heap_committed,gc_count,gc_ms\n");
                while(!finished) {
                    var heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();long count=0,ms=0;
                    for(var gc:ManagementFactory.getGarbageCollectorMXBeans()){count+=Math.max(0,gc.getCollectionCount());ms+=Math.max(0,gc.getCollectionTime());}
                    writer.write(java.time.Instant.now()+","+phase+","+progress+","+heap.getUsed()+","+heap.getCommitted()+","+count+","+ms+"\n");writer.flush();Thread.sleep(1000);
                }
            } catch(Exception e){e.printStackTrace();}
        },"performance-memory-sampler");worker.setDaemon(true);worker.start();
    }
}
