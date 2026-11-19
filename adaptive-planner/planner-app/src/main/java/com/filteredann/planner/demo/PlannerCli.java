// SPDX-License-Identifier: Apache-2.0
package com.filteredann.planner.demo;

import com.fasterxml.jackson.databind.*;
import com.filteredann.planner.api.*;
import com.filteredann.planner.filter.*;
import java.nio.file.*;
import java.util.*;
import java.io.*;

/** CLI product entry: one graph, interactive requests or a small reproducible demo. */
public final class PlannerCli {
    private static final ObjectMapper JSON=new ObjectMapper();
    private PlannerCli() { }
    public static void main(String[] args)throws Exception {
        Map<String,String> options=parse(args);
        if(options.containsKey("help")) {help();return;}
        Path root=Path.of(options.getOrDefault("root",".")).toAbsolutePath().normalize();
        boolean synthetic=options.containsKey("synthetic");
        int nodes=Integer.parseInt(options.getOrDefault("nodes","250000"));
        try(var runtime=PlannerRuntime.open(root,nodes,synthetic);var api=runtime.newSession()) {
            if(options.containsKey("interactive")){interactive(runtime,api);return;}
            if(options.containsKey("request")) {
                var tree=JSON.readTree(Path.of(options.get("request")).toFile());
                QueryRequest request=JSON.treeToValue(tree,QueryRequest.class);
                display("json-request",request,api.query(request));return;
            }
            if(options.containsKey("filter")) {
                int queryId=Integer.parseInt(options.getOrDefault("query-id","0"));
                FilterSpec filter=filter(options.get("filter"));
                double recall=Double.parseDouble(options.getOrDefault("recall","0.95"));
                Integer max=options.containsKey("max-budget")?Integer.valueOf(options.get("max-budget")):null;
                boolean cold=options.containsKey("cold");
                run(runtime,api,"single-request",queryId,filter,recall,max,cold,options.containsKey("no-gt"));return;
            }
            // Default query examples.
            run(runtime,api,"high-selectivity-R",0,FilterSpec.known("R-rank-100000"),.95,null,false,false);
            run(runtime,api,"low-selectivity-R",0,FilterSpec.known("R-rank-1000"),.95,null,false,false);
            run(runtime,api,"cluster-correlated-C",0,FilterSpec.known("C-rank-100000"),.95,null,false,false);
            run(runtime,api,"unknown-region-fallback",0,FilterSpec.equalTo(FilterSpec.Field.REGION,1),.95,null,false,false);
            run(runtime,api,"cold-known-filter-fallback",0,FilterSpec.known("R-rank-100000"),.95,null,true,false);
            run(runtime,api,"low-recall-P-route",0,FilterSpec.known("R-rank-100000"),.05,null,false,false);
            run(runtime,api,"expanded-candidates-O-route",0,FilterSpec.known("R-rank-100000"),.20,null,false,false);
        }
    }
    private static void run(PlannerRuntime runtime,QueryApi api,String name,int id,FilterSpec filter,double recall,Integer max,boolean cold,boolean noTruth)throws Exception {
        if(!cold){var prepared=runtime.prepare(filter);System.out.printf(Locale.ROOT,"FILTER_PREPARED scenario=%s matches=%d preparationMs=%.3f (outside request; next lookup HOT)%n",name,prepared.matches(),prepared.acquisitionNanos()/1e6);}
        float[] query=runtime.queryVector(id);var request=new QueryRequest(query,filter,recall,max,cold);
        var truth=noTruth?null:runtime.truth(filter,id,query);
        display(name,request,api.query(request,truth));
    }
    private static void display(String name,QueryRequest request,QueryResponse response)throws IOException {
        System.out.println("\n=== "+name+" ===");
        System.out.printf(Locale.ROOT,"INPUT filter=%s targetRecall=%.4f maxCandidateK=%s dimension=%d%n",request.filter().signature(),request.targetRecall(),request.maxCandidateK(),request.queryVector().length);
        var d=response.decision();
        System.out.printf(Locale.ROOT,"PLAN strategy=%s budget=%d cache=%s matches=%d selectivity=%.6f safeFallback=%s%n",d.strategy(),d.budget(),d.cacheState(),d.matches(),d.selectivity(),d.safeFallback());
        System.out.println("WHY "+d.reason());
        System.out.printf(Locale.ROOT,"HISTORICAL_EVIDENCE minRecall=%s meanMs=%s p99Ms=%s sourceBuild=%s; CURRENT_BUILD_PREDICTION=%s%n",d.historicalMinRecall(),d.historicalMeanMs(),d.historicalP99Ms(),d.sourceBuildId(),d.currentBuildPredictedMs()==null?"UNKNOWN":d.currentBuildPredictedMs());
        System.out.printf(Locale.ROOT,"RESULT executed=%s budget=%d ids=%s actualRecall=%s tieAware=%s targetMet=%s executionFallback=%s latencyMs=%.3f%n",response.selectedStrategy(),response.selectedBudget(),response.resultVectorIds(),response.actualRecall(),response.actualTieAwareRecall(),response.actualTargetMet(),response.executionFallback(),response.queryLatencyNanos()/1e6);
        System.out.println("GT_VERIFIED "+(response.actualRecall()!=null)+"; ATTEMPTS "+response.attempts());
        for(String warning:response.warnings())System.out.println("NOTE "+warning);
        System.out.println("RESPONSE_JSON "+JSON.writeValueAsString(response));
    }
    private static void interactive(PlannerRuntime runtime,QueryApi api)throws Exception {
        var in=new BufferedReader(new InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("Enter queryId | filter | targetRecall | optional maxCandidateK | hot/cold. Example: 0 | R-rank-100000 | .95 | 40 | hot. 'exit' quits.");
        for(String line;(line=in.readLine())!=null;) {
            if(line.strip().equalsIgnoreCase("exit"))return;
            try {
                String[] f=line.split("\\|",-1);if(f.length<3||f.length>5)throw new IllegalArgumentException("Expected 3..5 fields");
                Integer budget=f.length>3&&!f[3].isBlank()?Integer.valueOf(f[3].strip()):null;
                run(runtime,api,"interactive",Integer.parseInt(f[0].strip()),filter(f[1].strip()),Double.parseDouble(f[2].strip()),budget,f.length>4&&f[4].strip().equalsIgnoreCase("cold"),false);
            } catch(IllegalArgumentException e){System.out.println("REQUEST_ERROR "+e.getMessage());}
        }
    }
    public static FilterSpec filter(String text) {
        if(text.startsWith("R-")||text.startsWith("C-"))return FilterSpec.known(text);
        List<FilterSpec.Condition> terms=new ArrayList<>();
        for(String term:text.split("&",-1)) {
            var m=java.util.regex.Pattern.compile("\\s*(random_rank|category|region|cluster_rank)\\s*(<|=)\\s*(-?\\d+)\\s*").matcher(term);
            if(!m.matches())throw new IllegalArgumentException("Filter syntax: random_rank<1000&category=0 (no eval)");
            var field=switch(m.group(1)){case "random_rank"->FilterSpec.Field.RANDOM_RANK;case "category"->FilterSpec.Field.CATEGORY;case "region"->FilterSpec.Field.REGION;default->FilterSpec.Field.CLUSTER_RANK;};
            terms.add(new FilterSpec.Condition(field,m.group(2).equals("<")?FilterSpec.Operator.LT:FilterSpec.Operator.EQ,Integer.parseInt(m.group(3))));
        }
        return new FilterSpec(terms);
    }
    private static Map<String,String> parse(String[] args) {
        Set<String> flags=Set.of("help","synthetic","interactive","cold","no-gt");
        Set<String> valued=Set.of("root","nodes","filter","query-id","recall","max-budget","request");Map<String,String> options=new LinkedHashMap<>();
        for(int i=0;i<args.length;i++) {
            if(!args[i].startsWith("--"))throw new IllegalArgumentException("Expected --option: "+args[i]);
            String key=args[i].substring(2);
            if(flags.contains(key))options.put(key,"true");
            else if(valued.contains(key)&&i+1<args.length)options.put(key,args[++i]);
            else throw new IllegalArgumentException("Unknown/missing option: "+key);
        }
        return options;
    }
    private static void help(){System.out.println("Adaptive Filtered ANN Planner V0.3\n--root <repository> [--synthetic] [--nodes 250000]\n[--interactive] or [--filter <known-id or conjunction> --query-id 0 --recall .95 --max-budget 40 --cold --no-gt]\n[--request request.json] accepts a real float query vector and typed filter. Default runs seven query examples.");}
}
