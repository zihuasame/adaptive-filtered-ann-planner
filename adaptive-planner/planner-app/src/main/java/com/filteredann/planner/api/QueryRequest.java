// SPDX-License-Identifier: Apache-2.0
package com.filteredann.planner.api;

import com.filteredann.planner.filter.FilterSpec;
import java.util.Objects;

/** maxCandidateK is an ANN cap, never a request to invent an unmeasured budget. */
public record QueryRequest(float[] queryVector,FilterSpec filter,double targetRecall,Integer maxCandidateK,boolean bypassBitmapCache) {
    public QueryRequest {
        Objects.requireNonNull(queryVector);Objects.requireNonNull(filter);queryVector=queryVector.clone();
        if(queryVector.length==0)throw new IllegalArgumentException("Empty query vector");
        for(float x:queryVector)if(!Float.isFinite(x))throw new IllegalArgumentException("Nonfinite query vector");
        if(!Double.isFinite(targetRecall)||targetRecall<=0||targetRecall>1)throw new IllegalArgumentException("targetRecall must be in (0,1]");
        if(maxCandidateK!=null&&maxCandidateK<10)throw new IllegalArgumentException("maxCandidateK must be >=10");
    }
    public QueryRequest(float[] query,FilterSpec filter,double targetRecall){this(query,filter,targetRecall,null,false);}
    @Override public float[] queryVector(){return queryVector.clone();}
}
