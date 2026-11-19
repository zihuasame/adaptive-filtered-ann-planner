package com.filteredann.jvector;

import com.filteredann.dataset.Dataset;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Called only after a timed batch has finished. */
public final class JVectorResultExporter implements AutoCloseable {
    private final BufferedWriter latency, results, stats, summary;
    public JVectorResultExporter(Path output) throws IOException {
        Files.createDirectories(output);
        latency=Files.newBufferedWriter(output.resolve("jvector-query-latency.csv"));
        results=Files.newBufferedWriter(output.resolve("jvector-query-results.csv"));
        stats=Files.newBufferedWriter(output.resolve("jvector-search-stats.csv"));
        summary=Files.newBufferedWriter(output.resolve("jvector-summary.csv"));
        latency.write("query_id,candidate_k,rerank_k,repeat_id,thread_count,latency_ns,result_count,visited_count,expanded_count,expanded_base_layer,reranked_count,query_preparation_ns,score_provider_preparation_ns,result_conversion_ns,end_to_end_ns\n");
        results.write("query_id,candidate_k,rank,vector_id,jvector_score,exact_squared_l2,strict_ground_truth_hit,tie_aware_ground_truth_hit,top10_boundary_distance,tie_tolerance,official_top10_ids\n");
        stats.write("query_id,candidate_k,repeat_id,thread_count,visited_count,expanded_count,expanded_base_layer,reranked_count\n");
        summary.write("candidate_k,rerank_k,recall_at_10,tie_aware_recall_at_10,mean_latency_ms,p50_ms,p95_ms,p99_ms,min_ms,max_ms,qps,average_visited_count,average_expanded_count,average_expanded_base_layer,repeat_id,thread_count,end_to_end_p50_ms,end_to_end_p95_ms,end_to_end_p99_ms,average_reranked_count,measured_elapsed_ns,query_count\n");
    }
    public Map<String,Object> exportBatch(Dataset base,Dataset queries,int[][] gt,int[] order,
            int candidateK,int repeat,int threads,JVectorBenchmarkRunner.Batch batch,boolean saveNeighbors) throws IOException {
        long[] graph=new long[order.length], endToEnd=new long[order.length];
        double recall=0,tieRecall=0,visited=0,expanded=0,baseExpanded=0,reranked=0;
        for(int pos=0;pos<order.length;pos++) {
            int queryId=order[pos]; var r=batch.outcomes()[pos];
            var eval=JVectorMetrics.evaluate(base,queries.vector(queryId),r,gt[queryId],10);
            graph[pos]=r.graphSearchNanos(); endToEnd[pos]=r.endToEndNanos();
            recall+=eval.strictRecall(); tieRecall+=eval.tieAwareRecall();
            visited+=r.visitedCount(); expanded+=r.expandedCount(); baseExpanded+=r.expandedBaseLayer(); reranked+=r.rerankedCount();
            latency.write(queryId+","+candidateK+","+candidateK+","+repeat+","+threads+","+r.graphSearchNanos()+","+r.ids().length+","+r.visitedCount()+","+r.expandedCount()+","+r.expandedBaseLayer()+","+r.rerankedCount()+","+r.queryPreparationNanos()+","+r.scorePreparationNanos()+","+r.conversionNanos()+","+r.endToEndNanos()+"\n");
            stats.write(queryId+","+candidateK+","+repeat+","+threads+","+r.visitedCount()+","+r.expandedCount()+","+r.expandedBaseLayer()+","+r.rerankedCount()+"\n");
            if(saveNeighbors) {
                String truth=String.join(";",Arrays.stream(gt[queryId],0,10).mapToObj(Integer::toString).toList());
                for(int rank=0;rank<r.ids().length;rank++) results.write(queryId+","+candidateK+","+(rank+1)+","+r.ids()[rank]+","+Float.toString(r.scores()[rank])+","+Double.toString(eval.exactDistances()[rank])+","+eval.strictHits()[rank]+","+eval.tieAwareHits()[rank]+","+eval.boundaryDistance()+",0.0,"+truth+"\n");
            }
        }
        Map<String,Object> metrics=new LinkedHashMap<>(JVectorMetrics.latency(graph,batch.elapsedNanos()));
        metrics.put("candidateK",candidateK); metrics.put("rerankK",candidateK); metrics.put("repeatId",repeat); metrics.put("threadCount",threads);
        metrics.put("recall",recall/order.length); metrics.put("tieAwareRecall",tieRecall/order.length);
        metrics.put("averageVisited",visited/order.length); metrics.put("averageExpanded",expanded/order.length);
        metrics.put("averageExpandedBaseLayer",baseExpanded/order.length); metrics.put("averageReranked",reranked/order.length);
        metrics.put("endToEndP99Ms",JVectorMetrics.nearestRank(endToEnd,.99)/1e6);
        summary.write(candidateK+","+candidateK+","+recall/order.length+","+tieRecall/order.length+","+metrics.get("meanMs")+","+metrics.get("p50Ms")+","+metrics.get("p95Ms")+","+metrics.get("p99Ms")+","+metrics.get("minMs")+","+metrics.get("maxMs")+","+metrics.get("qps")+","+visited/order.length+","+expanded/order.length+","+baseExpanded/order.length+","+repeat+","+threads+","+JVectorMetrics.nearestRank(endToEnd,.5)/1e6+","+JVectorMetrics.nearestRank(endToEnd,.95)/1e6+","+metrics.get("endToEndP99Ms")+","+reranked/order.length+","+batch.elapsedNanos()+","+order.length+"\n");
        latency.flush(); results.flush(); stats.flush(); summary.flush(); return metrics;
    }
    @Override public void close() throws IOException { latency.close(); results.close(); stats.close(); summary.close(); }
}
