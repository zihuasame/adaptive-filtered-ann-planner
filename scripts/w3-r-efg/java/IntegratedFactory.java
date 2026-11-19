package com.filteredann.w3efg;
import com.filteredann.jvector.*;

import io.github.jbellis.jvector.graph.*;
import io.github.jbellis.jvector.graph.similarity.BuildScoreProvider;
import io.github.jbellis.jvector.management.GraphIndexBuilderConfig;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

public final class IntegratedFactory {
    public record BuildParameters(int maxDegree, int beamWidth, float neighborOverflow, float alpha,
            boolean addHierarchy, boolean refineFinalGraph, String compression, int simdThreads, int parallelThreads) {
        public BuildParameters {
            if (maxDegree <= 0 || beamWidth < maxDegree || !Float.isFinite(neighborOverflow) || neighborOverflow < 1
                    || !Float.isFinite(alpha) || alpha < 1 || !"NONE".equals(compression)
                    || simdThreads < 1 || parallelThreads < 1) throw new IllegalArgumentException("Invalid/uncompressed build parameters");
        }
        public static BuildParameters baseline() { return new BuildParameters(32,100,1.2f,1.2f,true,true,"NONE",16,16); }
    }
    public record BuiltIndex(ImmutableGraphIndex graph, Map<String,Object> performance,
                             Map<String,Object> structure) implements AutoCloseable {
        @Override public void close() throws Exception { graph.close(); }
    }
    public BuiltIndex build(CanonicalVectors store, BuildParameters parameters, JVectorMetrics.MemoryMonitor monitor) throws Exception {
        JVectorRuntimeInfo.requirePanama();
        long initStart = System.nanoTime();
        GraphIndexBuilderConfig config = GraphIndexBuilderConfig.getInstance();
        config.setAddHierarchy(parameters.addHierarchy()); config.setRefineFinalGraph(parameters.refineFinalGraph());
        config.setBuildCompressionType(parameters.compression()); config.setParallelBuild(true);
        ForkJoinPool simd = new ForkJoinPool(parameters.simdThreads());
        ForkJoinPool parallel = new ForkJoinPool(parameters.parallelThreads());
        GraphIndexBuilder builder = null;
        ImmutableGraphIndex graph = null;
        boolean succeeded = false;
        try {
            BuildScoreProvider scorer = BuildScoreProvider.randomAccessScoreProvider(store.values(),VectorSimilarityFunction.EUCLIDEAN);
            if (!scorer.isExact()) throw new IllegalStateException("Build scorer must be exact");
            builder = GraphIndexBuilder.builder(scorer,store.dimensions(),parameters.maxDegree())
                    .withBeamWidth(parameters.beamWidth()).withNeighborOverflow(parameters.neighborOverflow())
                    .withAlpha(parameters.alpha()).withSimdExecutor(simd).withParallelExecutor(parallel).build();
            long initNanos = System.nanoTime()-initStart;
            GraphIndexBuilder active = builder;
            long insertStart = System.nanoTime();
            simd.submit(() -> IntStream.range(0,store.count()).parallel().forEach(id -> {
                if ((id & 1023) == 0 && monitor != null) monitor.requireBuildHeadroom();
                active.addGraphNode(id,store.values().getVector(id));
            })).join();
            long insertNanos = System.nanoTime()-insertStart;
            long cleanupStart = System.nanoTime(); builder.cleanup(); long cleanupNanos = System.nanoTime()-cleanupStart;
            graph = builder.getGraph();
            if (graph.size(0) != store.count() || graph.getDimension() != store.dimensions()
                    || graph.isHierarchical() != parameters.addHierarchy()) throw new IllegalStateException("Graph shape mismatch");
            for (int id=0;id<store.count();id++) if (!graph.containsNode(id)) throw new IllegalStateException("Missing graph ID " + id);
            Map<String,Object> performance = new LinkedHashMap<>();
            performance.put("builderInitializationNanos",initNanos); performance.put("graphInsertionNanos",insertNanos);
            performance.put("cleanupFinalizationNanos",cleanupNanos);
            performance.put("builderTotalNanos",initNanos+insertNanos+cleanupNanos);
            performance.put("graphLibraryRamBytes",graph.ramBytesUsed());
            performance.put("builderLibraryRamBytesIncludingGraph",builder.ramBytesUsed());
            performance.put("builderAccountingNote","Library estimate includes graph, omits some scratch/thread-local allocations; do not sum with graph again.");
            List<Map<String,Object>> levels = new ArrayList<>();
            OnHeapGraphIndex heap = (OnHeapGraphIndex)graph;
            for(int level=0;level<=graph.getMaxLevel();level++) levels.add(Map.of("level",level,"nodeCount",graph.size(level),"averageDegree",heap.getAverageDegree(level)));
            Map<String,Object> structure = Map.of("indexType","JVector Hierarchical Vamana Graph Index",
                    "nodeCount",graph.size(0),"maxLevel",graph.getMaxLevel(),"hierarchical",graph.isHierarchical(),"levels",levels);
            succeeded = true;
            return new BuiltIndex(graph,performance,structure);
        } finally {
            try { if (builder != null) builder.close(); }
            finally {
                simd.close(); parallel.close();
                if (!succeeded && graph != null) graph.close();
            }
        }
    }
}
