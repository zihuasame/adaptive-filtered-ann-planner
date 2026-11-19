// SPDX-License-Identifier: Apache-2.0
package com.filteredann.planner.api;

import com.filteredann.filter.FilterBitmap;
import com.filteredann.planner.catalog.PerformanceCatalog;
import com.filteredann.planner.core.Planner;
import com.filteredann.planner.engine.*;
import com.filteredann.planner.filter.FilterEngine;
import io.github.jbellis.jvector.graph.ImmutableGraphIndex;
import java.util.*;

/** Worker-owned query session. Graph/filter cache may be shared; Searcher never is. */
public final class QueryApi implements AutoCloseable {
    private final RowsVectorStore vectors;
    private final FourStrategyEngine engine;
    private final FilterEngine filters;
    private final Planner planner;
    private final Planner.Context context;
    private final long owner=Thread.currentThread().threadId();
    public QueryApi(RowsVectorStore vectors,ImmutableGraphIndex graph,FilterEngine filters,PerformanceCatalog catalog,Planner.Context context) {
        if(vectors.count()!=filters.count() || vectors.count()!=context.count() || vectors.dimensions()!=context.dimensions())throw new IllegalArgumentException("Runtime dataset domain");
        this.vectors=vectors;this.filters=filters;this.context=context;this.planner=new Planner(catalog,context);
        this.engine=new FourStrategyEngine(vectors,vectors,graph);
    }
    public QueryResponse query(QueryRequest request){return query(request,null);}
    public QueryResponse query(QueryRequest request,GroundTruth truth) {
        return execute(request,truth,null,0);
    }
    public void exactKernel(com.filteredann.index.SearchIndex kernel){
        if(Thread.currentThread().threadId()!=owner)throw new IllegalStateException("Session thread");
        engine.exactKernel(kernel);
    }
    /** Fixed-policy control through the same request path; no GT-driven budget selection. */
    public QueryResponse queryFixed(QueryRequest request,com.filteredann.planner.engine.FourStrategyEngine.Strategy strategy,int budget) {
        Objects.requireNonNull(strategy);
        if(strategy!=com.filteredann.planner.engine.FourStrategyEngine.Strategy.E && !Set.of(10,40,160,640).contains(budget))throw new IllegalArgumentException("Measured budget required");
        return execute(request,null,strategy,budget);
    }
    private QueryResponse execute(QueryRequest request,GroundTruth truth,com.filteredann.planner.engine.FourStrategyEngine.Strategy fixed,int fixedBudget) {
        if(Thread.currentThread().threadId()!=owner)throw new IllegalStateException("Use one QueryApi session per thread");
        long start=System.nanoTime();float[] q=request.queryVector();
        if(q.length!=vectors.dimensions())throw new IllegalArgumentException("Query dimensions must be "+vectors.dimensions());
        var filter=filters.acquire(request.filter(),request.bypassBitmapCache());
        long validationNs=0;
        if(truth!=null){long a=System.nanoTime();validateTruth(truth,q,filter.bitmap(),filter.bitmapSha256());validationNs+=System.nanoTime()-a;}
        long planned=System.nanoTime();
        var decision=fixed==null?planner.plan(filter,request.targetRecall(),request.maxCandidateK()):new Planner.Decision(
            fixed,fixed==com.filteredann.planner.engine.FourStrategyEngine.Strategy.E?0:fixedBudget,null,filter.spec().signature(),filter.matches(),filter.selectivity(),filter.spec().conditions().size(),filter.cacheState(),request.targetRecall(),false,false,fixed==com.filteredann.planner.engine.FourStrategyEngine.Strategy.E,null,null,null,null,null,context.currentBuildId(),"FIXED_POLICY_CONTROL","Fixed strategy; budget frozen from historical Tuning only",List.of(),List.of());
        long planNs=System.nanoTime()-planned;
        var strategy=decision.strategy();int budget=decision.budget();
        List<QueryResponse.Attempt> attempts=new ArrayList<>();List<String> warnings=new ArrayList<>(decision.warnings());
        long searchStart=System.nanoTime();var result=engine.search(q,filter.bitmap(),strategy,Math.max(10,budget),Math.max(10,budget));long searchNs=System.nanoTime()-searchStart;
        validateIds(result.ids(),filter.bitmap());
        Double strict=null,tie=null;boolean fallback=false;
        if(truth!=null) {
            long a=System.nanoTime();var quality=evaluate(q,result.ids(),truth);strict=quality[0];tie=quality[1];validationNs+=System.nanoTime()-a;
        }
        attempts.add(new QueryResponse.Attempt(strategy,budget,result.ids().length,result.visited(),result.expanded(),searchNs,strict));
        if(strict!=null && strict<request.targetRecall() && strategy!=FourStrategyEngine.Strategy.E) {
            fallback=true;warnings.add("Provided GT detected insufficient actual Strict Recall; executed exact E, with both attempts retained");
            strategy=FourStrategyEngine.Strategy.E;budget=0;long a=System.nanoTime();
            result=engine.search(q,filter.bitmap(),strategy,10,10);long exactNs=System.nanoTime()-a;searchNs+=exactNs;
            validateIds(result.ids(),filter.bitmap());a=System.nanoTime();var quality=evaluate(q,result.ids(),truth);strict=quality[0];tie=quality[1];validationNs+=System.nanoTime()-a;
            attempts.add(new QueryResponse.Attempt(strategy,budget,result.ids().length,0,0,exactNs,strict));
        }
        if(truth==null)warnings.add("Actual Recall UNKNOWN: no query/filter/dataset-bound GT supplied; exact E is still exact enumeration");
        if(strict!=null&&strict<request.targetRecall())warnings.add("Final strict reference target not met; inspect supplied GT boundary tie convention; no false success reported");
        return new QueryResponse(decision,strategy,budget,Arrays.stream(result.ids()).boxed().toList(),strict,tie,
            strict==null?null:strict>=request.targetRecall(),fallback,System.nanoTime()-start,filter.acquisitionNanos(),planNs,searchNs,validationNs,attempts,warnings);
    }
    private void validateIds(int[] ids,FilterBitmap bitmap) {
        if(ids.length>Math.min(10,bitmap.cardinality()))throw new IllegalStateException("Result size");
        Set<Integer> seen=new HashSet<>();for(int id:ids)if(id<0||id>=vectors.count()||!seen.add(id)||!bitmap.get(id))throw new IllegalStateException("Illegal/duplicate/nonmatching result");
    }
    private void validateTruth(GroundTruth truth,float[] query,FilterBitmap bitmap,String bitmapHash) {
        if(!truth.datasetId().equals(context.datasetId()) || !truth.querySha256().equals(GroundTruth.queryHash(query)) || !truth.bitmapSha256().equals(bitmapHash))throw new IllegalArgumentException("GT belongs to a different dataset, query or bitmap");
        int[] ids=truth.ids();double[] distances=truth.squaredDistances();
        if(ids.length!=Math.min(10,bitmap.cardinality()))throw new IllegalArgumentException("GT count");
        validateIds(ids,bitmap);
        for(int i=0;i<ids.length;i++) {
            if(Double.compare(vectors.squaredL2(query,ids[i]),distances[i])!=0)throw new IllegalArgumentException("GT exact distance mismatch");
            if(i>0&&(distances[i]<distances[i-1] || distances[i]==distances[i-1]&&ids[i]<ids[i-1]))throw new IllegalArgumentException("GT must be sorted by (squaredL2,id)");
        }
    }
    private Double[] evaluate(float[] query,int[] ids,GroundTruth truth) {
        if(truth.ids().length==0)return new Double[]{null,null};
        Set<Integer> gt=new HashSet<>();for(int id:truth.ids())gt.add(id);
        double boundary=truth.squaredDistances()[truth.squaredDistances().length-1];int strict=0,tie=0;
        for(int id:ids) {
            double d=vectors.squaredL2(query,id);
            if(gt.contains(id))strict++;
            if(gt.contains(id)||d<=boundary)tie++;
            if(!gt.contains(id)&&d<boundary)throw new IllegalArgumentException("Supplied GT omitted a strictly closer vector");
        }
        return new Double[]{(double)strict/gt.size(),(double)tie/gt.size()};
    }
    @Override public void close()throws Exception{engine.close();}
}
