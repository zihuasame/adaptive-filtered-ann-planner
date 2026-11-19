package com.filteredann.w3efg;

import com.filteredann.dataset.Dataset;
import com.filteredann.filter.FilterBitmap;
import com.filteredann.index.BruteForceIndex;
import com.filteredann.jvector.JVectorVectorStore;
import io.github.jbellis.jvector.graph.*;
import io.github.jbellis.jvector.graph.similarity.DefaultSearchScoreProvider;
import io.github.jbellis.jvector.util.Bits;
import io.github.jbellis.jvector.vector.*;
import java.util.*;

/** One instance per worker, with a private Searcher. No resume or fallback. */
public final class IntegratedStrategies implements AutoCloseable {
    public enum Strategy { E,P,O,N }
    public record Outcome(int[] ids,float[] scores,int candidateCount,int visited,int expanded,int expandedBase,int reranked,
                          long queryPreparation,long scorePreparation,long filterAcquisition,long coreSearch,
                          long conversion,long postFilter,long endToEnd) { }
    private final CanonicalVectors base;
    private final CanonicalVectors store;
    private final GraphSearcher searcher;
    private final RowExactIndex exact;
    private boolean hooks=true;
    public void timingHooks(boolean enabled){hooks=enabled;exact.timingHooks(enabled);}
    private long clock(){return hooks?System.nanoTime():0L;}
    private final long owner=Thread.currentThread().threadId();
    public IntegratedStrategies(CanonicalVectors base,CanonicalVectors store,ImmutableGraphIndex graph) {
        if(graph.size(0)!=base.count() || graph.getIdUpperBound()!=base.count() || store.count()!=base.count()
                || graph.getDimension()!=base.dimensions()) throw new IllegalArgumentException("Graph/ID/vector domain mismatch");
        this.base=base; this.store=store; searcher=new GraphSearcher(graph); exact=new RowExactIndex(base);
    }
    public Outcome search(float[] query,FilterBitmap filter,Strategy strategy,int budget,int limit) {
        return search(query,()->filter,strategy,budget,limit);
    }
    public Outcome search(Dataset queries,int queryId,java.util.function.Supplier<FilterBitmap> acquisition,Strategy strategy,int budget,int limit) {
        long start=clock(); float[] query=queries.vector(queryId); long prepared=clock();
        Outcome o=search(query,acquisition,strategy,budget,limit);
        return new Outcome(o.ids,o.scores,o.candidateCount,o.visited,o.expanded,o.expandedBase,o.reranked,
            o.queryPreparation+prepared-start,o.scorePreparation,o.filterAcquisition,o.coreSearch,o.conversion,o.postFilter,clock()-start);
    }
    public Outcome search(float[] query,java.util.function.Supplier<FilterBitmap> acquisition,Strategy strategy,int budget,int limit) {
        if(Thread.currentThread().threadId()!=owner) throw new IllegalStateException("Searcher shared across threads");
        Objects.requireNonNull(strategy);
        if(query==null || query.length!=base.dimensions()) throw new IllegalArgumentException("Query dimensions");
        for(float value:query) if(!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite query");
        if(strategy!=Strategy.E && (budget<10 || limit<10 || limit>base.count())) throw new IllegalArgumentException("Candidate budget/domain");
        long start=clock();
        FilterBitmap filter=Objects.requireNonNull(acquisition.get()); long acquired=clock();
        if(filter.length()!=base.count()) throw new IllegalArgumentException("Bitmap domain mismatch");
        if(filter.cardinality()==0) return new Outcome(new int[0],new float[0],0,0,0,0,0,0,0,acquired-start,0,0,0,clock()-start);
        if(strategy==Strategy.E) {
            long core=clock(); var result=exact.search(query,filter,10); long searched=clock();
            int[] ids=result.neighbors().stream().mapToInt(n->n.vectorId()).toArray();
            float[] scores=new float[ids.length]; Arrays.fill(scores,Float.NaN); long end=clock();
            return new Outcome(ids,scores,ids.length,0,0,0,0,0,0,acquired-start,searched-core,end-searched,0,end-start);
        }
        long preparation=clock();
        var vector=VectorizationProvider.getInstance().getVectorTypeSupport().createFloatVector(query);
        long prepared=clock();
        var provider=DefaultSearchScoreProvider.exact(vector,VectorSimilarityFunction.EUCLIDEAN,store.values());
        long scored=clock(); int top=strategy==Strategy.O?limit:10;
        var result=searcher.search(provider,top,Math.max(budget,top),Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,strategy==Strategy.N?filter::get:Bits.ALL);
        long searched=clock(); var nodes=result.getNodes();
        int[] candidates=new int[nodes.length]; float[] candidateScores=new float[nodes.length];
        for(int i=0;i<nodes.length;i++) { candidates[i]=nodes[i].node; candidateScores[i]=nodes[i].score; }
        long converted=clock(); int count=0; int[] ids=new int[10]; float[] scores=new float[10];
        for(int i=0;i<candidates.length && count<10;i++) {
            if(strategy==Strategy.N || filter.get(candidates[i])) { ids[count]=candidates[i]; scores[count++]=candidateScores[i]; }
        }
        ids=Arrays.copyOf(ids,count); scores=Arrays.copyOf(scores,count); long end=clock();
        return new Outcome(ids,scores,candidates.length,result.getVisitedCount(),result.getExpandedCount(),result.getExpandedCountBaseLayer(),result.getRerankedCount(),
            prepared-preparation,scored-prepared,acquired-start,searched-scored,converted-searched,end-converted,end-start);
    }
    @Override public void close() throws Exception { searcher.close(); }
}
