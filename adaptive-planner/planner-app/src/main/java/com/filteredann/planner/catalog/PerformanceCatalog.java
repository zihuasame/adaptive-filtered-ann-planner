// SPDX-License-Identifier: Apache-2.0
package com.filteredann.planner.catalog;

import com.fasterxml.jackson.databind.*;
import com.filteredann.planner.engine.FourStrategyEngine.Strategy;
import com.filteredann.planner.filter.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Historical observations, never predictions for an unmeasured runtime or query. */
public final class PerformanceCatalog {
    public record Round(int round,double strictRecall,double tieAwareRecall,double p50Ms,double p95Ms,double p99Ms,
                        double e2eMeanMs,double qps,double visitedCount,double expandedCount,double insufficientRate,int queries,
                        double bitmapMeanMs,double bitmapP99Ms,double workerE2EMeanMs,double workerE2EP99Ms,double graphSearchP99Ms) { }
    public record Point(Strategy strategy,int budget,String scenario,List<Round> rounds,double strictMin,
                        double tieMedian,double e2eMeanMedianMs,double p99MedianMs) {
        public Point { rounds=List.copyOf(rounds); }
    }
    public record FilterEvidence(String id,FilterSpec spec,String bitmapSha256,int matches,double selectivity,
                                 String family,int conditionCount,List<Point> points) {
        public FilterEvidence { points=List.copyOf(points); }
        public List<Point> tuning() { return points.stream().filter(p->p.scenario.equals("tuning")).toList(); }
    }
    public record DatasetIdentity(String id,int count,int dimensions,String baseSha256,String metadataSha256,String labelsSha256,String querySha256) { }
    private final DatasetIdentity dataset;
    private final String sourceRunId,sourceBuildId;
    private final Map<String,FilterEvidence> bySignature;
    private final JsonNode document;
    public PerformanceCatalog(Path path) throws IOException {
        Path manifest=path.resolveSibling("source-manifest.json");
        if(!Files.isRegularFile(manifest))throw new IOException("Missing catalog provenance manifest");
        String expected=new ObjectMapper().readTree(manifest.toFile()).required("catalogSha256").asText();
        if(!com.filteredann.util.Artifacts.sha256(path).equals(expected))throw new IOException("Catalog checksum mismatch; rebuild catalog from measured inputs, never edit performance values");
        document=new ObjectMapper().readTree(path.toFile());
        if(document.path("schemaVersion").asInt()!=1 || document.path("topK").asInt()!=10)throw new IOException("Unsupported catalog schema/topK");
        dataset=new ObjectMapper().treeToValue(document.required("dataset"),DatasetIdentity.class);
        if(dataset.count<10 || dataset.dimensions<1)throw new IOException("Catalog dataset shape");
        sourceRunId=document.required("sourceRunId").asText();sourceBuildId=document.required("sourceBuildId").asText();
        Map<String,FilterEvidence> filters=new LinkedHashMap<>();
        for(var f:document.required("filters")) {
            String id=f.required("id").asText();var spec=FilterSpec.known(id);List<Point> points=new ArrayList<>();
            for(var p:f.required("points")) {
                Strategy s=Strategy.valueOf(p.required("strategy").asText());int budget=p.required("budget").asInt();
                if(s==Strategy.E?budget!=0:!Set.of(10,40,160,640).contains(budget))throw new IOException("Unmeasured catalog budget");
                String scenario=p.required("scenario").asText();
                if(!Set.of("tuning","holdout-hot","holdout-cold").contains(scenario))throw new IOException("Catalog split");
                List<Round> rounds=new ArrayList<>();Set<Integer> seen=new HashSet<>();
                for(var r:p.required("rounds")) {
                    var row=new ObjectMapper().treeToValue(r,Round.class);
                    if(!seen.add(row.round) || row.round<0 || row.round>2 || row.queries<1
                        || !finiteQuality(row.strictRecall) || !finiteQuality(row.tieAwareRecall)
                        || !finiteQuality(row.insufficientRate) || !positive(row.p50Ms) || !positive(row.p95Ms)
                        || !positive(row.p99Ms) || !positive(row.e2eMeanMs) || !positive(row.qps)
                        || !nonnegative(row.visitedCount) || !nonnegative(row.expandedCount)
                        || !nonnegative(row.bitmapMeanMs) || !nonnegative(row.bitmapP99Ms) || !positive(row.workerE2EMeanMs)
                        || !positive(row.workerE2EP99Ms) || !positive(row.graphSearchP99Ms))throw new IOException("Invalid catalog round");
                    rounds.add(row);
                }
                if(rounds.size()!=3)throw new IOException("Three independent rounds required");
                // Derive quality/cost from rounds, never trust cached summary fields or Holdout selection flags.
                points.add(new Point(s,budget,scenario,rounds,rounds.stream().mapToDouble(Round::strictRecall).min().orElseThrow(),
                    median(rounds.stream().mapToDouble(Round::tieAwareRecall).toArray()),median(rounds.stream().mapToDouble(Round::e2eMeanMs).toArray()),median(rounds.stream().mapToDouble(Round::p99Ms).toArray())));
            }
            int matches=f.required("matches").asInt();
            if(matches<0 || matches>dataset.count || points.stream().filter(p->p.scenario.equals("tuning")).count()!=13)throw new IOException("Catalog filter/grid shape");
            Set<String> unique=new HashSet<>();for(var p:points)if(!unique.add(p.scenario+":"+p.strategy+":"+p.budget))throw new IOException("Duplicate evidence point");
            var evidence=new FilterEvidence(id,spec,f.required("bitmapSha256").asText(),matches,(double)matches/dataset.count,f.required("family").asText(),spec.conditions().size(),points);
            if(filters.put(spec.signature(),evidence)!=null)throw new IOException("Duplicate filter signature");
        }
        if(filters.size()!=8)throw new IOException("V0.1 requires eight actual filter evidence groups");
        bySignature=Collections.unmodifiableMap(filters);
    }
    private static boolean positive(double x){return Double.isFinite(x)&&x>0;}
    private static boolean nonnegative(double x){return Double.isFinite(x)&&x>=0;}
    private static boolean finiteQuality(double x){return Double.isFinite(x)&&x>=0&&x<=1;}
    private static double median(double[] a){Arrays.sort(a);return a[1];}
    public DatasetIdentity dataset(){return dataset;}
    public String sourceRunId(){return sourceRunId;}
    public String sourceBuildId(){return sourceBuildId;}
    public Optional<FilterEvidence> find(FilterSpec spec){return Optional.ofNullable(bySignature.get(spec.signature()));}
    public Collection<FilterEvidence> filters(){return bySignature.values();}
    public JsonNode buildEvidence(){return document.required("historicalBuild").deepCopy();}
}
