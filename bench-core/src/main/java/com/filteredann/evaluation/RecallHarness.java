package com.filteredann.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.filteredann.dataset.*;
import com.filteredann.filter.*;
import com.filteredann.index.*;
import com.filteredann.index.SearchResult.Neighbor;
import com.filteredann.metadata.*;
import com.filteredann.util.Artifacts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.lang.management.*;
import java.nio.file.*;
import java.util.*;

public final class RecallHarness {
    private static final Logger LOG = LoggerFactory.getLogger(RecallHarness.class);
    public record Config(int baseCount, int queryCount, int dimensions, int k,
                         int querySampleSize, long seed, List<Integer> thresholds) {
        public Config {
            if (baseCount != 1_000_000 || queryCount != 10_000 || dimensions != 128
                    || k <= 0 || k > 100 || querySampleSize <= 0 || querySampleSize > 50
                    || seed != 20261113L || thresholds == null || thresholds.isEmpty()) {
                throw new IllegalArgumentException("W1 requires full SIFT1M, K in 1..100, and 1..50 sampled queries");
            }
            if (new HashSet<>(thresholds).size() != thresholds.size()) throw new IllegalArgumentException("Duplicate thresholds");
            for (int t : thresholds) if (t < 0 || t > baseCount) throw new IllegalArgumentException("Invalid threshold");
            thresholds = List.copyOf(thresholds);
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: RecallHarness <project-root> <config-yaml> <run-name>");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Config config = new ObjectMapper(new YAMLFactory()).readValue(Path.of(args[1]).toFile(), Config.class);
        if (!args[2].matches("[a-z0-9-]+")) throw new IllegalArgumentException("Invalid run name");
        new RecallHarness().run(root, config, args[2]);
    }

    public void run(Path root, Config config, String runName) throws Exception {
        long totalStart = System.nanoTime();
        Path output = root.resolve("results/" + runName + "/generated");
        Path gtOutput = root.resolve("data/processed/ground-truth/" + runName);
        Files.createDirectories(output); Files.createDirectories(gtOutput);
        Map<String,String> expected = Artifacts.JSON.readValue(root.resolve("config/checksums.json").toFile(), new TypeReference<>() { });
        Map<String,String> hashes = new TreeMap<>();
        List<Map<String,Object>> checksumReport = new ArrayList<>();
        for (String name : List.of("sift_base.fvecs", "sift_query.fvecs", "sift_groundtruth.ivecs")) {
            Path path = root.resolve("data/raw/" + name);
            String hash = Artifacts.sha256(path); hashes.put(name,hash);
            boolean matches = hash.equals(expected.get(name));
            checksumReport.add(Map.of("file", name, "bytes", Files.size(path), "sha256", hash, "matches", matches));
            Artifacts.json(output.resolve("checksum-report.json"), checksumReport);
            if (!matches) throw new IOException("SHA-256 mismatch; refusing dataset: " + path);
        }
        long loadStart = System.nanoTime();
        SiftDatasetReader reader = new SiftDatasetReader();
        Dataset base = reader.readVectors(root.resolve("data/raw/sift_base.fvecs"), config.baseCount(), config.dimensions());
        Dataset queries = reader.readVectors(root.resolve("data/raw/sift_query.fvecs"), config.queryCount(), config.dimensions());
        int[][] official = reader.readGroundTruth(root.resolve("data/raw/sift_groundtruth.ivecs"), config.queryCount(), config.baseCount());
        long loadNanos = System.nanoTime() - loadStart;
        LOG.info("Loaded {} base vectors, {} queries, official Top-100",base.count(),queries.count());
        long metadataStart = System.nanoTime();
        MetadataGenerator generator = new MetadataGenerator();
        MetadataStore metadata = generator.generate(base.count(),config.seed());
        Path metadataPath = root.resolve("data/processed/metadata/sift1m-random-v1.bin");
        generator.write(metadataPath,metadata,config.seed());
        String metadataHash = Artifacts.sha256(metadataPath);
        MetadataManifest metadataManifest = new MetadataManifest(1,base.count(),config.seed(),MetadataGenerator.ALGORITHM,
                MetadataGenerator.VERSION, new TreeMap<>(Map.of("vector_id","implicit zero-based record index", "filter_rank","independent random permutation 0..N-1",
                "category_id","uniform nextInt(16)", "region_id","uniform nextInt(8)")),
                new TreeMap<>(Map.of("filter_rank",base.count(),"category_id",16,"region_id",8)),hashes,metadataHash);
        Artifacts.json(metadataPath.resolveSibling("sift1m-random-v1.manifest.json"),metadataManifest);
        long metadataNanos = System.nanoTime() - metadataStart;

        Map<String,FilterBitmap> filters = new LinkedHashMap<>();
        Map<String,Long> bitmapNanos = new LinkedHashMap<>();
        for (int threshold : config.thresholds()) {
            long start = System.nanoTime();
            FilterBitmap bitmap = FilterBitmap.build(metadata,FilterPredicate.rankBelow(threshold));
            if (bitmap.cardinality() != threshold) throw new IllegalStateException("Rank permutation count mismatch");
            String id = "rank_lt_" + threshold; filters.put(id,bitmap); bitmapNanos.put(id,System.nanoTime()-start);
        }
        List<Integer> sample = sampleQueries(config.queryCount(),config.querySampleSize(),config.seed());
        Map<String,Object> manifest = new LinkedHashMap<>();
        manifest.put("datasetSha256",hashes); manifest.put("metadataSha256",metadataHash);
        manifest.put("randomSeed",config.seed()); manifest.put("randomAlgorithm",MetadataGenerator.ALGORITHM);
        manifest.put("randomAlgorithmVersion",MetadataGenerator.VERSION);
        manifest.put("querySampling","Fisher-Yates descending using separate java.util.Random(seed), take prefix, sort IDs");
        manifest.put("queryIds",sample); manifest.put("k",config.k()); manifest.put("distance","Squared L2; double subtraction and accumulation; no SIMD/mmap");
        manifest.put("tieBreak","distance ascending, vector_id ascending"); manifest.put("javaVersion",System.getProperty("java.version"));
        manifest.put("jvmArguments",ManagementFactory.getRuntimeMXBean().getInputArguments());
        manifest.put("projectGitCommit",gitCommit(root)); manifest.put("experimentConfig",config);
        manifest.put("emptyGroundTruth","not evaluable; CSV recall field is empty");
        Artifacts.json(output.resolve("run-manifest.json"),manifest);

        GroundTruthGenerator gt = new GroundTruthGenerator(base);
        List<Map<String,Object>> officialChecks = new ArrayList<>();
        List<Map<String,Object>> queryPerformance = new ArrayList<>();
        try (BufferedWriter summary = Files.newBufferedWriter(gtOutput.resolve("filtered-summary.csv"));
             BufferedWriter neighbors = Files.newBufferedWriter(gtOutput.resolve("filtered-neighbors.csv"));
             BufferedWriter comparison = Files.newBufferedWriter(output.resolve("recall-comparison.csv"))) {
            summary.write("query_id,filter_id,filter_count,selectivity,k_requested,gt_count\n");
            neighbors.write("query_id,filter_id,rank,vector_id,squared_l2\n");
            comparison.write("query_id,filter_id,k,filter_count,selectivity,postfilter_result_count,global_gt_count,filtered_gt_count,recall_vs_global_gt,recall_vs_filtered_gt,identity_expected\n");
            for (int queryId : sample) {
                float[] query = queries.vector(queryId);
                SearchResult global = gt.global(query,config.k());
                officialChecks.add(validateOfficial(base,query,queryId,global,official[queryId]));
                queryPerformance.add(performance(queryId,"all",global));
                for (var entry : filters.entrySet()) {
                    String filterId = entry.getKey(); FilterBitmap bitmap = entry.getValue();
                    SearchResult filtered = gt.filtered(query,bitmap,config.k());
                    List<Neighbor> candidates = global.neighbors().stream().filter(n -> bitmap.get(n.vectorId())).toList();
                    if (!filtered.neighbors().containsAll(candidates)) throw new IllegalStateException("C is not a subset of GF");
                    if (filtered.neighbors().size() != Math.min(config.k(),bitmap.cardinality())) throw new IllegalStateException("Wrong GT count");
                    OptionalDouble rg = RecallMetrics.recall(candidates,global.neighbors());
                    OptionalDouble rf = RecallMetrics.recall(candidates,filtered.neighbors());
                    boolean identity = bitmap.cardinality() >= config.k();
                    if (identity && Double.compare(rg.orElseThrow(),rf.orElseThrow()) != 0) throw new IllegalStateException("Recall identity failed");
                    double selectivity = Selectivity.of(bitmap);
                    summary.write(queryId+","+filterId+","+bitmap.cardinality()+","+selectivity+","+config.k()+","+filtered.neighbors().size()+"\n");
                    int rank=1;
                    for (Neighbor n : filtered.neighbors()) neighbors.write(queryId+","+filterId+","+(rank++)+","+n.vectorId()+","+Double.toString(n.squaredL2())+"\n");
                    comparison.write(queryId+","+filterId+","+config.k()+","+bitmap.cardinality()+","+selectivity+","+candidates.size()+","+global.neighbors().size()+","+filtered.neighbors().size()+","+csv(rg)+","+csv(rf)+","+identity+"\n");
                    queryPerformance.add(performance(queryId,filterId,filtered));
                }
                LOG.info("Completed query {} ({}/{})",queryId,sample.indexOf(queryId)+1,sample.size());
            }
        }
        Artifacts.json(output.resolve("official-validation.json"),officialChecks);
        Map<String,Object> performance = new LinkedHashMap<>();
        performance.put("dataLoadNanos",loadNanos); performance.put("metadataGenerationAndPersistenceNanos",metadataNanos);
        performance.put("bitmapBuildNanos",bitmapNanos); performance.put("queries",queryPerformance);
        performance.put("heapUsedBytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        performance.put("heapCommittedBytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getCommitted());
        performance.put("heapMaxBytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax());
        performance.put("heapPoolPeakUsedBytes",ManagementFactory.getMemoryPoolMXBeans().stream().filter(p->p.getType()==MemoryType.HEAP).mapToLong(p->p.getPeakUsage().getUsed()).sum());
        performance.put("totalRunNanos",System.nanoTime()-totalStart);
        Artifacts.json(output.resolve("performance.json"),performance);
        LOG.info("Run {} complete",runName);
    }

    public static List<Integer> sampleQueries(int count,int size,long seed) {
        if (count < 0 || size < 0 || size > count) throw new IllegalArgumentException("Invalid sample size");
        int[] ids = new int[count]; for(int i=0;i<count;i++) ids[i]=i;
        Random random = new Random(seed);
        for(int i=count-1;i>0;i--) { int j=random.nextInt(i+1), swap=ids[i]; ids[i]=ids[j]; ids[j]=swap; }
        return Arrays.stream(ids,0,size).sorted().boxed().toList();
    }
    private static String csv(OptionalDouble value) { return value.isPresent()?Double.toString(value.getAsDouble()):""; }
    private static Map<String,Object> performance(int queryId,String filterId,SearchResult result) {
        return Map.of("queryId",queryId,"filterId",filterId,"stats",result.stats());
    }
    private static String gitCommit(Path root) throws IOException,InterruptedException {
        Process process = new ProcessBuilder("git","-C",root.toString(),"rev-parse","HEAD").redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();
        if (process.waitFor() != 0) throw new IOException("Commit source before running experiment: " + output);
        return output;
    }
    private static Map<String,Object> validateOfficial(Dataset base,float[] query,int queryId,SearchResult global,int[] official) {
        double previous=-1;
        for(int id : official) {
            double distance=base.squaredL2(query,id);
            if(distance<previous) throw new IllegalStateException("Unsorted official GT for query " + queryId);
            previous=distance;
        }
        boolean sameIds=true;
        for(int rank=0;rank<global.neighbors().size();rank++) {
            Neighbor computed=global.neighbors().get(rank);
            double expectedDistance=base.squaredL2(query,official[rank]);
            if(computed.squaredL2()!=expectedDistance) throw new IllegalStateException("Official distance mismatch for query " + queryId + " rank " + rank);
            sameIds &= computed.vectorId()==official[rank];
        }
        return Map.of("queryId",queryId,"topKDistancesEqual",true,"orderedIdsEqual",sameIds,
                "policy","Official ties may use different IDs/order; exact distances at every rank must match. Our IDs use ascending tie-break.");
    }
}
