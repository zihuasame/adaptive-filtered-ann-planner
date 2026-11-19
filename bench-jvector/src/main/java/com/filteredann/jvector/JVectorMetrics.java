package com.filteredann.jvector;

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class JVectorMetrics {
    private JVectorMetrics() { }
    public record Evaluation(double strictRecall, double tieAwareRecall, double boundaryDistance,
            double[] exactDistances, boolean[] strictHits, boolean[] tieAwareHits) { }
    public static Evaluation evaluate(com.filteredann.dataset.Dataset base, float[] query,
            JVectorSearchIndex.Outcome result, int[] truth, int k) {
        JVectorSearchIndex.validateOutcome(result,base.count(),k);
        if(truth.length<k) throw new IllegalArgumentException("Ground truth shorter than K");
        Set<Integer> expected=new HashSet<>();
        List<com.filteredann.index.SearchResult.Neighbor> gt=new ArrayList<>(), retrieved=new ArrayList<>();
        for(int rank=0;rank<k;rank++) {
            expected.add(truth[rank]); gt.add(new com.filteredann.index.SearchResult.Neighbor(truth[rank],base.squaredL2(query,truth[rank])));
        }
        double boundary=gt.getLast().squaredL2();
        double[] distances=new double[result.ids().length]; boolean[] strict=new boolean[distances.length], tie=new boolean[distances.length];
        int tieCount=0;
        for(int rank=0;rank<distances.length;rank++) {
            int id=result.ids()[rank]; double d=base.squaredL2(query,id); distances[rank]=d;
            strict[rank]=expected.contains(id);
            tie[rank]=strict[rank] || d==boundary; // SIFT integer coordinates: exact double equality, tolerance=0.
            if(!strict[rank] && d<boundary) throw new IllegalStateException("Official GT omitted a strictly closer vector");
            if(tie[rank]) tieCount++;
            double expectedScore=1.0/(1.0+d);
            if(Math.abs(result.scores()[rank]-expectedScore)>Math.max(1e-12,Math.abs(expectedScore)*1e-5)) throw new IllegalStateException("EUCLIDEAN score direction/value mismatch");
            retrieved.add(new com.filteredann.index.SearchResult.Neighbor(id,d));
        }
        double strictRecall=com.filteredann.evaluation.RecallMetrics.recall(retrieved,gt).orElseThrow();
        return new Evaluation(strictRecall,(double)tieCount/k,boundary,distances,strict,tie);
    }
    public static long nearestRank(long[] values, double quantile) {
        if (values.length == 0 || quantile <= 0 || quantile > 1) throw new IllegalArgumentException("Invalid percentile");
        long[] sorted = values.clone(); Arrays.sort(sorted);
        return sorted[(int)Math.ceil(quantile * sorted.length)-1];
    }
    public static Map<String,Object> latency(long[] values, long elapsedNanos) {
        if (values.length == 0 || elapsedNanos <= 0) throw new IllegalArgumentException("No measured queries");
        LongSummaryStatistics stats = Arrays.stream(values).summaryStatistics();
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("meanMs",stats.getAverage()/1e6); result.put("p50Ms",nearestRank(values,0.50)/1e6);
        result.put("p95Ms",nearestRank(values,0.95)/1e6); result.put("p99Ms",nearestRank(values,0.99)/1e6);
        result.put("minMs",stats.getMin()/1e6); result.put("maxMs",stats.getMax()/1e6);
        result.put("qps",values.length/(elapsedNanos/1e9)); return result;
    }
    public static long heapUsed() { return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(); }
    public static final class MemoryMonitor implements AutoCloseable {
        private final long start = System.nanoTime();
        private final List<Map<String,Long>> samples = Collections.synchronizedList(new ArrayList<>());
        private final AtomicLong peak = new AtomicLong();
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r,"w2-memory-sampler"); t.setDaemon(true); return t;
        });
        public MemoryMonitor() { sample(); executor.scheduleAtFixedRate(this::sample,250,250,TimeUnit.MILLISECONDS); }
        public void sample() {
            long heap = heapUsed(); peak.accumulateAndGet(heap,Math::max);
            samples.add(Map.of("elapsedNanos",System.nanoTime()-start,"heapUsedBytes",heap,
                    "freePhysicalBytes",JVectorRuntimeInfo.osBean().getFreeMemorySize()));
        }
        public void requireBuildHeadroom() {
            long free = JVectorRuntimeInfo.osBean().getFreeMemorySize();
            if (free < 256L*1024*1024) throw new IllegalStateException("Physical memory reserve below 256 MiB; stopping build");
        }
        public Map<String,Object> report() {
            sample();
            List<Map<String,Long>> copy; synchronized(samples) { copy=List.copyOf(samples); }
            return Map.of("samplingIntervalMs",250,"sampledHeapPeakBytes",peak.get(),"samples",copy,
                    "heapMaxBytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax(),
                    "note","Sampled simultaneous heap peak; may miss shorter spikes. Library ramBytesUsed is an estimate, not an allocation census.");
        }
        @Override public void close() { executor.shutdownNow(); }
    }
}
