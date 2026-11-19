// SPDX-License-Identifier: Apache-2.0
package com.filteredann.planner.api;

import com.filteredann.planner.core.Planner.Decision;
import com.filteredann.planner.engine.FourStrategyEngine.Strategy;
import java.util.*;

public record QueryResponse(Decision decision,Strategy selectedStrategy,int selectedBudget,List<Integer> resultVectorIds,
        Double actualRecall,Double actualTieAwareRecall,Boolean actualTargetMet,boolean executionFallback,
        long queryLatencyNanos,long bitmapNanos,long planningNanos,long searchNanos,long verificationNanos,
        List<Attempt> attempts,List<String> warnings) {
    public record Attempt(Strategy strategy,int budget,int returnedCount,int visitedCount,int expandedCount,long searchNanos,Double actualRecall) { }
    public QueryResponse {resultVectorIds=List.copyOf(resultVectorIds);attempts=List.copyOf(attempts);warnings=List.copyOf(warnings);}
}
