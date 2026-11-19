package com.filteredann.jvector;

import com.filteredann.dataset.Dataset;
import com.filteredann.filter.FilterBitmap;
import com.filteredann.index.*;
import io.github.jbellis.jvector.graph.GraphSearcher;
import io.github.jbellis.jvector.graph.ImmutableGraphIndex;
import io.github.jbellis.jvector.graph.similarity.DefaultSearchScoreProvider;
import io.github.jbellis.jvector.util.Bits;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;
import java.io.IOException;
import java.util.*;

/** One instance per thread; no filter implementation. Exact validation is outside ANN timing. */
public final class JVectorSearchIndex implements SearchIndex, AutoCloseable {
    public record Outcome(int[] ids, float[] scores, int visitedCount, int expandedCount,
            int expandedBaseLayer, int rerankedCount, long queryPreparationNanos,
            long scorePreparationNanos, long graphSearchNanos, long conversionNanos, long endToEndNanos) { }
    private final Dataset base;
    private final JVectorVectorStore store;
    private final GraphSearcher searcher;
    private final int defaultCandidateK;
    private final long owner = Thread.currentThread().threadId();
    private final VectorTypeSupport support = VectorizationProvider.getInstance().getVectorTypeSupport();
    private boolean closed;
    public JVectorSearchIndex(Dataset base, JVectorVectorStore store, ImmutableGraphIndex graph, int candidateK) {
        if (base.count()!=store.count() || graph.size(0)!=store.count() || base.dimensions()!=store.dimensions()) throw new IllegalArgumentException("Index/vector shape mismatch");
        if(candidateK <= 0) throw new IllegalArgumentException("Invalid candidateK");
        this.base=base; this.store=store; this.defaultCandidateK=candidateK; searcher=new GraphSearcher(graph);
    }
    @Override public String name() { return "JVector-4.0.2-Hierarchical-Vamana-unfiltered"; }
    private void checkOwner() {
        if (closed || Thread.currentThread().threadId()!=owner) throw new IllegalStateException("Searcher is closed or accessed by another thread");
    }
    public Outcome search(Dataset queries, int queryId, JVectorSearchOptions options) {
        checkOwner(); long start=System.nanoTime();
        return execute(queries.vector(queryId),options,start);
    }
    public Outcome search(float[] query, JVectorSearchOptions options) {
        checkOwner(); long start=System.nanoTime();
        return execute(query.clone(),options,start);
    }
    private Outcome execute(float[] query,JVectorSearchOptions options,long start) {
        if(query.length!=store.dimensions()) throw new IllegalArgumentException("Query dimension mismatch");
        for(float f:query) if(!Float.isFinite(f)) throw new IllegalArgumentException("Non-finite query");
        var vector=support.createFloatVector(query); long prepared=System.nanoTime();
        var provider=DefaultSearchScoreProvider.exact(vector,VectorSimilarityFunction.EUCLIDEAN,store.values());
        long scored=System.nanoTime();
        var result=searcher.search(provider,options.topK(),options.rerankK(),Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Bits.ALL);
        long searched=System.nanoTime();
        var nodes=result.getNodes(); int[] ids=new int[nodes.length]; float[] scores=new float[nodes.length];
        for(int rank=0;rank<nodes.length;rank++) { ids[rank]=nodes[rank].node; scores[rank]=nodes[rank].score; }
        long converted=System.nanoTime();
        return new Outcome(ids,scores,result.getVisitedCount(),result.getExpandedCount(),result.getExpandedCountBaseLayer(),result.getRerankedCount(),
                prepared-start,scored-prepared,searched-scored,converted-searched,converted-start);
    }
    public static void validateOutcome(Outcome result,int baseCount,int k) {
        if(result.ids().length!=Math.min(k,baseCount) || result.scores().length!=result.ids().length) throw new IllegalStateException("Unexpected result count");
        Set<Integer> seen=new HashSet<>(); float previous=Float.POSITIVE_INFINITY;
        for(int rank=0;rank<result.ids().length;rank++) {
            int id=result.ids()[rank]; float score=result.scores()[rank];
            if(id<0 || id>=baseCount || !seen.add(id) || !Float.isFinite(score) || score>previous) throw new IllegalStateException("Invalid IDs/scores/order");
            previous=score;
        }
    }
    @Override public SearchResult search(float[] query,FilterBitmap filter,int k) {
        if(filter==null || filter.length()!=base.count()) throw new IllegalArgumentException("Bitmap length mismatch");
        if(filter.cardinality()!=base.count()) throw new UnsupportedOperationException("W2 supports only unfiltered search");
        Outcome outcome=search(query,new JVectorSearchOptions(k,defaultCandidateK));
        validateOutcome(outcome,base.count(),k);
        List<SearchResult.Neighbor> neighbors=new ArrayList<>();
        for(int id:outcome.ids()) neighbors.add(new SearchResult.Neighbor(id,base.squaredL2(query,id)));
        neighbors.sort(SearchResult.ORDER);
        // -1 explicitly means unknown, never substitute visitedCount for distance evaluations.
        return new SearchResult(neighbors,new SearchStats(base.count(),-1,outcome.endToEndNanos()));
    }
    @Override public void close() throws IOException { checkOwner(); closed=true; searcher.close(); }
}
