package com.filteredann.w3;

import com.filteredann.dataset.Dataset;
import com.filteredann.filter.FilterBitmap;
import com.filteredann.jvector.*;
import com.filteredann.util.Artifacts;
import io.github.jbellis.jvector.graph.*;
import io.github.jbellis.jvector.graph.similarity.*;
import io.github.jbellis.jvector.util.Bits;
import io.github.jbellis.jvector.vector.*;
import java.nio.file.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/** Micro-fixture diagnostics only: not a W3 strategy or a production graph algorithm. */
public final class W3Phase0Probe {
    private static final List<Map<String,Object>> cases=new ArrayList<>();
    static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
    static final class Counting {
        final AtomicInteger calls=new AtomicInteger(), rejectedCalls=new AtomicInteger();
        final List<Integer> scored=new ArrayList<>();
        final SearchScoreProvider provider;
        Counting(float[] query,JVectorVectorStore store,Bits bits) {
            var vector=VectorizationProvider.getInstance().getVectorTypeSupport().createFloatVector(query);
            var exact=DefaultSearchScoreProvider.exact(vector,VectorSimilarityFunction.EUCLIDEAN,store.values());
            ScoreFunction.ExactScoreFunction scorer=id->{ calls.incrementAndGet(); scored.add(id); if(!bits.get(id)) rejectedCalls.incrementAndGet(); return exact.scoreFunction().similarityTo(id); };
            provider=new DefaultSearchScoreProvider(scorer);
        }
    }
    public static final class ChainGraph implements ImmutableGraphIndex {
        final int n,dimensions,cutAfter;
        public ChainGraph(int n,int dimensions,int cutAfter) { this.n=n; this.dimensions=dimensions; this.cutAfter=cutAfter; }
        @Override public int size(int level) { return level==0?n:0; }
        @Override public int getIdUpperBound() { return n; }
        @Override public int maxDegree() { return 1; }
        @Override public List<Integer> maxDegrees() { return List.of(1); }
        @Override public int getDegree(int level) { return 1; }
        @Override public double getAverageDegree(int level) { return n==0?0:(double)(n-1)/n; }
        @Override public int getDimension() { return dimensions; }
        @Override public boolean isHierarchical() { return false; }
        @Override public int getMaxLevel() { return 0; }
        @Override public void close() { }
        @Override public NodesIterator getNodes(int level) { return NodesIterator.fromPrimitiveIterator(IntStream.range(0,size(level)).iterator(),size(level)); }
        @Override public View getView() {
            return new View() {
                @Override public NodesIterator getNeighborsIterator(int level,int node) {
                    int end=(node+1<n && node!=cutAfter)?node+2:node+1;
                    return NodesIterator.fromPrimitiveIterator(IntStream.range(node+1,end).iterator(),end-node-1);
                }
                @Override public void processNeighbors(int level,int node,ScoreFunction scorer,IntMarker marker,NeighborProcessor consumer) {
                    var it=getNeighborsIterator(level,node);
                    while(it.hasNext()) { int id=it.nextInt(); if(marker.mark(id)) consumer.process(id,scorer.similarityTo(id)); }
                }
                @Override public int size() { return n; }
                @Override public int getIdUpperBound() { return n; }
                @Override public NodeAtLevel entryNode() { return n==0?null:new NodeAtLevel(0,0); }
                @Override public Bits liveNodes() { return Bits.ALL; }
                @Override public boolean contains(int level,int id) { return level==0 && id>=0 && id<n; }
                @Override public void close() { }
            };
        }
        @Override public long ramBytesUsed() { return 0; }
    }
    static Map<String,Object> describe(String name,SearchResult result,Counting count,int topK,int budget,int matches) {
        Map<String,Object> row=new LinkedHashMap<>(); row.put("name",name); row.put("topK",topK); row.put("rerankK",budget); row.put("matchingCount",matches);
        row.put("ids",Arrays.stream(result.getNodes()).mapToInt(n->n.node).toArray()); row.put("scores",Arrays.stream(result.getNodes()).map(n->n.score).toList());
        row.put("returnedCount",result.getNodes().length); row.put("visited",result.getVisitedCount()); row.put("expanded",result.getExpandedCount());
        row.put("expandedBase",result.getExpandedCountBaseLayer()); row.put("reranked",result.getRerankedCount());
        row.put("diagnosticScorerCalls",count.calls.get()); row.put("nonmatchingScorerCalls",count.rejectedCalls.get()); row.put("scoredIds",List.copyOf(count.scored));
        return row;
    }
    static SearchResult runCase(String name,ImmutableGraphIndex graph,JVectorVectorStore store,float[] query,FilterBitmap filter,int topK,int budget,int expected) throws Exception {
        checkedDomain(graph,store,filter,topK,budget);
        Bits bits=filter::get; Counting count=new Counting(query,store,bits);
        try(var searcher=new GraphSearcher(graph)) {
            var result=searcher.search(count.provider,topK,budget,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,bits);
            Set<Integer> seen=new HashSet<>();
            for(var node:result.getNodes()) require(node.node>=0 && node.node<store.count() && seen.add(node.node) && filter.get(node.node),"Illegal/filter-mismatched/duplicate ID");
            require(result.getNodes().length==expected,"Fixture expected count differs: "+name);
            require(result.getRerankedCount()==0,"Exact provider unexpectedly reranked");
            Map<String,Object> row=describe(name,result,count,topK,budget,filter.cardinality()); row.put("expectedCount",expected); row.put("passed",true); cases.add(row); return result;
        }
    }
    static void checkedDomain(ImmutableGraphIndex graph,JVectorVectorStore store,FilterBitmap filter,int k,int budget) {
        if(k<=0 || budget<k || filter==null || filter.length()!=graph.size(0) || graph.size(0)!=store.count()
                || graph.getIdUpperBound()!=store.count() || graph.getDimension()!=store.dimensions()) throw new IllegalArgumentException("Probe domain/K/budget mismatch");
    }
    static void reject(String name,Runnable call) {
        try { call.run(); throw new AssertionError("Not rejected: "+name); }
        catch(IllegalArgumentException|IndexOutOfBoundsException expected) { cases.add(Map.of("name",name,"passed",true,"exception",expected.toString())); }
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]),output=Path.of(args[1]); Files.createDirectories(output);
        if(args.length>2 && args[2].equals("sleep")) { Thread.sleep(60000); return; }
        if(args.length>2 && args[2].equals("memory")) {
            try(var writer=Files.newBufferedWriter(output.resolve("heap-samples.csv"))) {
                writer.write("elapsed_ns,heap_used_bytes,free_physical_bytes\n"); long start=System.nanoTime(); List<byte[]> retained=new ArrayList<>();
                for(int i=0;i<12;i++) {
                    retained.add(new byte[1024*1024]);
                    writer.write((System.nanoTime()-start)+","+ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()+","+JVectorRuntimeInfo.osBean().getFreeMemorySize()+"\n"); writer.flush(); Thread.sleep(250);
                }
            } return;
        }
        var runtime=JVectorRuntimeInfo.capture(root.resolve(".tools/jvector-4.0.2/jvector-4.0.2-sources.jar"));
        Artifacts.json(output.resolve("runtime-info.json"),runtime);
        float[] values=new float[32]; for(int i=0;i<32;i++) values[i]=i;
        Dataset data=new Dataset(values,1); var store=new JVectorVectorStore(data); var chain=new ChainGraph(32,1,-1);
        Artifacts.json(output.resolve("inputs.json"),Map.of("vectors",values,"query",new float[]{31},"graph","directed chain 0->1->...->31; entry=0","fixtureOnly",true));
        runCase("ALL",chain,store,new float[]{31},FilterBitmap.all(32),10,40,10);
        runCase("NONE-direct-library",chain,store,new float[]{31},FilterBitmap.of(32),10,40,0);
        runCase("singleton-bridge",chain,store,new float[]{31},FilterBitmap.of(32,31),10,40,1);
        for(int n:new int[]{9,10,11}) runCase("matching-"+n,chain,store,new float[]{31},FilterBitmap.of(32,IntStream.range(32-n,32).toArray()),10,40,Math.min(10,n));
        runCase("disconnected-unreachable-is-valid-underfill",new ChainGraph(32,1,15),store,new float[]{31},FilterBitmap.of(32,31),10,40,0);
        cases.add(Map.of("name","NONE-application-short-circuit-probe","matchingCount",0,"returnedCount",0,"scorerCalls",0,"passed",true));
        reject("application-topK-zero",()->checkedDomain(chain,store,FilterBitmap.all(32),0,10));
        reject("application-topK-negative",()->checkedDomain(chain,store,FilterBitmap.all(32),-1,10));
        reject("application-rerankK-below-topK",()->checkedDomain(chain,store,FilterBitmap.all(32),10,9));
        reject("application-bitmap-length-mismatch",()->checkedDomain(chain,store,FilterBitmap.all(33),10,40));
        reject("application-ID-domain-mismatch",()->checkedDomain(new ChainGraph(33,1,-1),store,FilterBitmap.all(33),10,40));
        reject("bitmap-negative-ID",()->FilterBitmap.of(32,-1)); reject("bitmap-upper-bound-ID",()->FilterBitmap.of(32,32));
        try(var searcher=new GraphSearcher(chain)) {
            var count=new Counting(new float[]{0},store,Bits.ALL);
            reject("library-null-Bits",()->searcher.search(count.provider,10,40,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,null));
            reject("library-rerankK-below-topK",()->searcher.search(count.provider,10,9,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Bits.ALL));
            for(int badK:new int[]{0,-1}) {
                Map<String,Object> observed=new LinkedHashMap<>(); observed.put("name","library-raw-topK-"+badK); observed.put("expectation","Observed only; application rejects before calling library");
                try { var r=searcher.search(count.provider,badK,40,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Bits.ALL); observed.put("returnedCount",r.getNodes().length); }
                catch(RuntimeException e) { observed.put("exception",e.toString()); } cases.add(observed);
            }
        }
        try(var searcher=new GraphSearcher(chain)) {
            var count=new Counting(new float[]{0},store,Bits.ALL);
            var first=searcher.search(count.provider,5,5,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Bits.ALL);
            cases.add(describe("resume-initial-search",first,count,5,5,32)); int before=count.calls.get();
            var next=searcher.resume(3,8); var row=describe("Experimental-resume-additional-3",next,count,3,8,32);
            row.put("scorerCallsDuringResume",count.calls.get()-before); require(next.getNodes().length<=3,"resume treated as cumulative K"); cases.add(row);
            var repeated=searcher.search(count.provider,5,5,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Bits.ALL);
            require(Arrays.equals(Arrays.stream(first.getNodes()).mapToInt(n->n.node).toArray(),Arrays.stream(repeated.getNodes()).mapToInt(n->n.node).toArray()),"Repeated search state leaked");
            cases.add(describe("fresh-search-after-resume",repeated,count,5,5,32));
        }
        runCase("empty-graph",new ChainGraph(0,1,-1),new JVectorVectorStore(new Dataset(new float[0],1)),new float[]{0},FilterBitmap.of(0),10,40,0);
        Random random=new Random(20261113); float[] randomRows=new float[256*8]; for(int i=0;i<randomRows.length;i++) randomRows[i]=random.nextFloat();
        Dataset randomData=new Dataset(randomRows,8); var randomStore=new JVectorVectorStore(randomData);
        try(var built=new JVectorIndexFactory().build(randomStore,JVectorIndexFactory.BuildParameters.baseline(),null)) {
            for(int topK:new int[]{1,5,10}) for(int budget:new int[]{10,40,128}) runCase("independent-topK-budget",built.graph(),randomStore,randomData.vector(0),FilterBitmap.all(256),topK,budget,topK);
        }
        Dataset nearTies=new Dataset(new float[]{4096,1,4096,0},2); var tieStore=new JVectorVectorStore(nearTies);
        var tied=runCase("float-score-tie-distinct-double-distance",new ChainGraph(2,2,-1),tieStore,new float[]{0,0},FilterBitmap.all(2),1,1,1);
        cases.add(Map.of("name","double-float-tie-evidence","doubleDistances",new double[]{nearTies.squaredL2(new float[]{0,0},0),nearTies.squaredL2(new float[]{0,0},1)},"doubleClosestId",1,"actualId",tied.getNodes()[0].node,"isApproximationObservation",true));
        try(var executor=Executors.newFixedThreadPool(8)) {
            List<Future<Map<String,Object>>> futures=new ArrayList<>();
            for(int worker=0;worker<8;worker++) {
                final int id=worker;
                futures.add(executor.submit(()->{
                    try(var searcher=new GraphSearcher(chain)) {
                        var count=new Counting(new float[]{31},store,Bits.ALL);
                        int[] first=null;
                        for(int r=0;r<5;r++) {
                            var result=searcher.search(count.provider,10,40,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Bits.ALL);
                            int[] ids=Arrays.stream(result.getNodes()).mapToInt(n->n.node).toArray();
                            if(first==null) first=ids; else require(Arrays.equals(first,ids),"thread state leaked");
                        }
                        return Map.of("name","independent-searcher-worker","worker",id,"threadId",Thread.currentThread().threadId(),"ids",first,"passed",true);
                    }
                }));
            }
            for(var future:futures) cases.add(future.get());
        }
        require(cases.stream().filter(r->r.get("name").equals("singleton-bridge")).anyMatch(r->((Number)r.get("nonmatchingScorerCalls")).intValue()>0),"Bridge scoring not observed");
        Artifacts.json(output.resolve("probe-cases.json"),cases);
        Artifacts.json(output.resolve("probe-status.json"),Map.of("allAssertionsPassed",true,"caseCount",cases.size(),"scope","Micro fixtures only; not a recall guarantee for arbitrary graphs; no W3 strategy/performance implementation"));
        System.out.println("Phase0 micro-probe assertions passed; evidence cases="+cases.size());
    }
}
