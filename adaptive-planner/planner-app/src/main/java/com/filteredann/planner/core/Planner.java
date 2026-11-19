// SPDX-License-Identifier: Apache-2.0
package com.filteredann.planner.core;

import com.filteredann.planner.catalog.PerformanceCatalog;
import com.filteredann.planner.catalog.PerformanceCatalog.*;
import com.filteredann.planner.engine.FourStrategyEngine.Strategy;
import com.filteredann.planner.filter.FilterEngine.*;
import java.util.*;

/** V0.1: quality first, then the previously approved three-round median E2E mean rule. */
public final class Planner {
    public record Context(String datasetId,int count,int dimensions,boolean verifiedCatalogDataset,String currentBuildId) { }
    public record Alternative(Strategy strategy,int budget,Double historicalMinRecall,Double historicalMeanMs,String status) { }
    public record Decision(Strategy strategy,int budget,String filterId,String signature,int matches,double selectivity,
                           int conditionCount,CacheState cacheState,double targetRecall,boolean safeFallback,
                           boolean supportedByHistoricalEvidence,boolean exactEnumeration,Double historicalMinRecall,
                           Double historicalMeanMs,Double historicalP99Ms,Double currentBuildPredictedMs,
                           String sourceBuildId,String currentBuildId,String evidenceScope,String reason,
                           List<Alternative> alternatives,List<String> warnings) {
        public Decision { alternatives=List.copyOf(alternatives);warnings=List.copyOf(warnings); }
    }
    private final PerformanceCatalog catalog;
    private final Context context;
    public Planner(PerformanceCatalog catalog,Context context){this.catalog=Objects.requireNonNull(catalog);this.context=Objects.requireNonNull(context);}
    public Decision plan(Prepared filter,double targetRecall,Integer maxCandidateK) {
        if(!Double.isFinite(targetRecall)||targetRecall<=0||targetRecall>1)throw new IllegalArgumentException("targetRecall must be in (0,1]");
        if(maxCandidateK!=null&&maxCandidateK<10)throw new IllegalArgumentException("maxCandidateK must be >=10");
        List<Alternative> alternatives=new ArrayList<>();
        List<String> warnings=new ArrayList<>();
        var evidence=catalog.find(filter.spec()).orElse(null);
        boolean shape=context.verifiedCatalogDataset && context.datasetId.equals(catalog.dataset().id())
            && context.count==catalog.dataset().count()&&context.dimensions==catalog.dataset().dimensions();
        boolean covered=shape && evidence!=null && evidence.matches()==filter.matches() && evidence.bitmapSha256().equals(filter.bitmapSha256());
        if(targetRecall==1 || filter.matches()<10)return exact(filter,targetRecall,evidence,covered,false,"Exact result requirement or fewer than ten matching vectors",alternatives,warnings);
        if(!covered)return exact(filter,targetRecall,evidence,false,true,"Unknown filter signature, dataset identity, bitmap contents or selectivity; exact fallback",alternatives,warnings);
        if(filter.cacheState()==CacheState.COLD)return exact(filter,targetRecall,evidence,true,true,"No cold Tuning measurements: Holdout cold rows cannot choose a plan; exact fallback",alternatives,warnings);
        List<Point> eligible=new ArrayList<>();
        for(var p:evidence.tuning()) {
            String status;
            if(p.strategy()!=Strategy.E && maxCandidateK!=null&&p.budget()>maxCandidateK)status="BUDGET_CAP";
            else if(p.strictMin()<targetRecall)status="RECALL_BELOW_TARGET";
            else {status="ELIGIBLE";eligible.add(p);}
            alternatives.add(new Alternative(p.strategy(),p.budget(),p.strictMin(),p.e2eMeanMedianMs(),status));
        }
        if(eligible.isEmpty())return exact(filter,targetRecall,evidence,true,true,"No measured configuration meets the requested quality and budget",alternatives,warnings);
        var chosen=eligible.stream().min(Comparator.comparingDouble(Point::e2eMeanMedianMs).thenComparingInt(Point::budget).thenComparing(p->p.strategy().name())).orElseThrow();
        for(int i=0;i<alternatives.size();i++) {
            var a=alternatives.get(i);
            if(a.status().equals("ELIGIBLE"))alternatives.set(i,new Alternative(a.strategy(),a.budget(),a.historicalMinRecall(),a.historicalMeanMs(),a.strategy()==chosen.strategy()&&a.budget()==chosen.budget()?"SELECTED":"HIGHER_COST_OR_TIE_BREAK"));
        }
        if(!catalog.sourceBuildId().equals(context.currentBuildId))warnings.add("Historical single-graph evidence reused as a prior on a new graph; current-build latency prediction is UNKNOWN");
        warnings.add("Historical Recall is an average over 1000 Tuning queries, not a per-query guarantee. Actual Recall requires bound Ground Truth.");
        return new Decision(chosen.strategy(),chosen.budget(),evidence.id(),filter.spec().signature(),filter.matches(),filter.selectivity(),filter.spec().conditions().size(),filter.cacheState(),targetRecall,false,true,chosen.strategy()==Strategy.E,chosen.strictMin(),chosen.e2eMeanMedianMs(),chosen.p99MedianMs(),null,catalog.sourceBuildId(),context.currentBuildId,
            "HISTORICAL_TUNING_ONLY_SAME_DATA_AND_PARAMETERS", "Every Tuning round meets target; lowest median of three E2E means; no interpolated budget and no Holdout selection",alternatives,warnings);
    }
    private Decision exact(Prepared filter,double target,FilterEvidence evidence,boolean covered,boolean fallback,String why,List<Alternative> alternatives,List<String> warnings) {
        warnings.add("Current-build/cold latency prediction is UNKNOWN; exact enumeration preserves correctness, not a latency SLA");
        if(evidence!=null)for(var p:evidence.tuning())alternatives.add(new Alternative(p.strategy(),p.budget(),p.strictMin(),p.e2eMeanMedianMs(),"NOT_USED: "+why));
        return new Decision(Strategy.E,0,evidence==null?null:evidence.id(),filter.spec().signature(),filter.matches(),filter.selectivity(),filter.spec().conditions().size(),filter.cacheState(),target,fallback,covered,true,null,null,null,null,catalog.sourceBuildId(),context.currentBuildId,"EXACT_ENUMERATION_WITHOUT_CALIBRATED_COST",why,alternatives,warnings);
    }
}
