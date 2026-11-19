package com.filteredann.w3;

import static com.filteredann.w3.W3MinimalRunner.*;
import com.filteredann.dataset.*;
import com.filteredann.index.SearchResult.Neighbor;
import com.filteredann.jvector.*;
import com.filteredann.util.Artifacts;
import com.filteredann.w3full.FullPreparation;
import java.nio.file.*;
import java.util.*;

/** Complete full-size workload. Watchdog failures leave pending cells, never success. */
public final class W3FullRunner {
    static Cell selectedCell(Path run,String id) throws Exception {
        for(var row:Artifacts.JSON.readTree(run.resolve("schedule-plan.json").toFile())) {
            if(row.get("id").asText().equals(id)) return new Cell(id,"reuse",row.get("filter").asText(),
                W3Strategies.Strategy.valueOf(row.get("strategy").asText()),row.get("budget").asInt(),row.get("repeat").asInt(),row.get("threads").asInt());
        }
        throw new IllegalArgumentException("Unknown scheduled cell: "+id);
    }
    static void unit(String name) { System.out.println("W3UNIT,START,"+name); }
    static void end(String name) { System.out.println("W3UNIT,END,"+name); }
    static Inputs load(Path root,Path run) throws Exception {
        var valid=Artifacts.JSON.readTree(run.resolve("prepared/independent-validation.json").toFile());
        if(!valid.get("passed").asBoolean() || valid.get("groups").asInt()!=190000) throw new IllegalStateException("Full GT validation required");
        var manifest=Artifacts.JSON.readTree(run.resolve("prepared/labels-manifest.json").toFile());
        if(!Artifacts.sha256(run.resolve("prepared/labels.bin")).equals(manifest.get("labelHash").asText())) throw new IllegalStateException("Label hash changed");
        Dataset base=new SiftDatasetReader().readVectors(root.resolve("data/raw/sift_base.fvecs"),1000000,128);
        Dataset raw=new SiftDatasetReader().readVectors(root.resolve("data/raw/sift_query.fvecs"),10000,128);
        int[] ids=integers(manifest.get("queryOrder")); float[] queryRows=new float[10000*128];
        for(int i=0;i<10000;i++) System.arraycopy(raw.vector(ids[i]),0,queryRows,i*128,128);
        return new Inputs(base,new Dataset(queryRows,128),FullPreparation.metadata(root),ids);
    }
    static Inputs subset(Inputs in,int count) {
        float[] rows=new float[count*128]; for(int i=0;i<count;i++) System.arraycopy(in.queries().vector(i),0,rows,i*128,128);
        return new Inputs(in.base(),new Dataset(rows,128),in.metadata(),Arrays.copyOf(in.queryIds(),count));
    }
    static boolean meets(List<Summary> rows,String filter,W3Strategies.Strategy strategy,int budget,int threads,double target) {
        var selected=rows.stream().filter(s->s.cache().equals("reuse") && s.filter().equals(filter) && s.strategy().equals(strategy.toString()) && s.budget()==budget && s.threads()==threads).toList();
        return selected.size()==3 && selected.stream().map(Summary::repeat).distinct().count()==3
            && selected.stream().allMatch(s->s.repeat()>=0 && s.repeat()<3 && s.strict()>=target);
    }
    static Summary measure(Path root,Path run,String buildId,Inputs in,JVectorVectorStore store,JVectorIndexFactory.BuiltIndex built,
        Cell cell,W3Workload.Filter filter,List<List<Neighbor>> gt,boolean online,List<Summary> completed) throws Exception {
        unit(cell.id());
        Artifacts.json(run.resolve("active-cell.json"),Map.of("cell",cell,"state","running","completedCells",completed.size(),"startedEpochMillis",System.currentTimeMillis()));
        long[] before=gc();
        Batch result=batch(in,store,built,cell,online?()->filter.build(in.metadata()):filter::bitmap,1000); long[] after=gc();
        Summary row=export(root,run,buildId,in,cell,filter,gt,result,before,after); completed.add(row);
        Path checkpoint=run.resolve("completed/"+cell.id()+".json"),partial=run.resolve("completed/"+cell.id()+".json.partial");
        Artifacts.json(partial,row); Files.move(partial,checkpoint,StandardCopyOption.ATOMIC_MOVE);
        Artifacts.json(run.resolve(cell.cache().equals("rebuild")?"rebuild-summary.json":"full-summary.json"),completed);
        Artifacts.json(run.resolve("active-cell.json"),Map.of("cell",cell,"state","completed","completedCells",completed.size(),"endedEpochMillis",System.currentTimeMillis()));
        end(cell.id()); return row;
    }
    static void mainMatrix(Path root,Path run,Inputs in,List<W3Workload.Filter> filters,JVectorVectorStore store,JVectorIndexFactory.BuiltIndex built,Map<String,List<List<Neighbor>>> truth) throws Exception {
        int[] budgets={10,20,40,80,160,320,640,1280}; String buildId=run.getFileName()+"-graph1";
        List<Cell> blocks=new ArrayList<>(),schedule=new ArrayList<>();
        for(var filter:filters) for(int threads:new int[]{1,8}) for(var strategy:W3Strategies.Strategy.values()) for(int budget:strategy==W3Strategies.Strategy.E?new int[]{0}:budgets)
            blocks.add(new Cell("","reuse",filter.id(),strategy,budget,0,threads));
        Collections.shuffle(blocks,new Random(20261115));
        for(int repeat=0;repeat<3;repeat++) for(int i=0;i<blocks.size();i++) {
            var block=blocks.get((i+repeat*37)%blocks.size()); schedule.add(new Cell(String.format("full-hot-%04d",schedule.size()),"reuse",block.filter(),block.strategy(),block.budget(),repeat,block.threads()));
        }
        if(schedule.size()!=2850) throw new AssertionError("Full matrix must contain 2850 cells");
        Artifacts.json(run.resolve("full-schedule.json"),schedule); List<Summary> completed=new ArrayList<>();
        Map<String,W3Workload.Filter> byId=new HashMap<>(); filters.forEach(f->byId.put(f.id(),f));
        for(var cell:schedule) {
            measure(root,run,buildId,in,store,built,cell,byId.get(cell.filter()),truth.get(cell.filter()),false,completed);
            Artifacts.json(run.resolve("matrix-progress.json"),Map.of("scope","w3-full-v1","state","running","mainCompleted",completed.size(),"mainExpected",2850));
        }
        List<Map<String,Object>> triggers=new ArrayList<>(); int extension=0;
        for(var filter:filters) for(var strategy:new W3Strategies.Strategy[]{W3Strategies.Strategy.P,W3Strategies.Strategy.O,W3Strategies.Strategy.N}) for(int threads:new int[]{1,8}) {
            if(Arrays.stream(budgets).anyMatch(b->meets(completed,filter.id(),strategy,b,threads,.99))) continue;
            for(int budget:new int[]{2560,5120,10000}) {
                triggers.add(Map.of("filter",filter.id(),"strategy",strategy,"threads",threads,"budget",budget,"reason","No measured budget achieves Strict>=0.99 in all three rounds"));
                Artifacts.json(run.resolve("extension-triggers.json"),triggers);
                for(int repeat=0;repeat<3;repeat++) measure(root,run,buildId,in,store,built,new Cell(String.format("full-ext-%04d",extension++),"reuse",filter.id(),strategy,budget,repeat,threads),filter,truth.get(filter.id()),false,completed);
                if(meets(completed,filter.id(),strategy,budget,threads,.99)) break;
            }
        }
        System.out.println("W3STAGE,START,online");
        List<Map<String,Object>> points=new ArrayList<>(); int onlineIndex=0; Inputs online=subset(in,1000);
        for(var filter:filters) for(var strategy:W3Strategies.Strategy.values()) {
            int[] measured=completed.stream().filter(s->s.cache().equals("reuse") && s.filter().equals(filter.id()) && s.strategy().equals(strategy.toString()) && s.threads()==1).mapToInt(Summary::budget).distinct().sorted().toArray();
            int budget=selected(completed,filter.id(),strategy,measured);
            points.add(Map.of("filter",filter.id(),"strategy",strategy,"budget",budget,"target",.95,"achievedAllThree",meets(completed,filter.id(),strategy,budget,1,.95)));
            for(int repeat=0;repeat<3;repeat++) measure(root,run,buildId,online,store,built,new Cell(String.format("full-online-%03d",onlineIndex++),"online",filter.id(),strategy,budget,repeat,1),filter,truth.get(filter.id()).subList(0,1000),true,completed);
        }
        Artifacts.json(run.resolve("full-quality-points.json"),points);
        Artifacts.json(run.resolve("matrix-progress.json"),Map.of("state","measured-awaiting-independent-validation","mainCompleted",2850,"extensionsCompleted",extension,"onlineCompleted",onlineIndex));
    }
    static void ancillary(Path root,Path run,Inputs in,List<W3Workload.Filter> filters,JVectorVectorStore store,JVectorIndexFactory.BuiltIndex built,Map<String,List<List<Neighbor>>> truth,String mode,String[] args) throws Exception {
        var points=Artifacts.JSON.readTree(run.resolve("full-quality-points.json").toFile());
        if(mode.equals("first-use")) {
            String filterId=args[3],strategyName=args[4]; int sample=Integer.parseInt(args[5]);
            var filter=filters.stream().filter(f->f.id().equals(filterId)).findFirst().orElseThrow(); var strategy=W3Strategies.Strategy.valueOf(strategyName);
            int budget=-1; for(var p:points) if(p.get("filter").asText().equals(filterId) && p.get("strategy").asText().equals(strategyName)) budget=p.get("budget").asInt();
            if(budget<0) throw new IllegalStateException("Missing preregistered quality point");
            String id=filterId+"-"+strategyName+"-"+sample; unit("first-"+id);
            try(var searcher=new W3Strategies(in.base(),store,built.graph())) {
                var result=searcher.search(in.queries(),0,filter::bitmap,strategy,budget,Math.max(10,budget));
                var quality=W3Validation.validate(in.base(),in.queries().vector(0),filter.bitmap(),result.ids(),truth.get(filterId).getFirst());
                Artifacts.json(run.resolve("first-use/"+id+".json"),Map.of("filter",filterId,"strategy",strategyName,"sample",sample,"budget",budget,"queryId",in.queryIds()[0],
                    "buildId",run.getFileName()+"-first-"+id,"outcome",result,"quality",quality,"note","One first explicit query per fresh JVM; graph construction already ran; not hardware cold cache"));
                double strict=0,tie=0;
                for(int q=0;q<100;q++) {
                    var audit=searcher.search(in.queries(),q,filter::bitmap,strategy,budget,Math.max(10,budget));
                    var checked=W3Validation.validate(in.base(),in.queries().vector(q),filter.bitmap(),audit.ids(),truth.get(filterId).get(q));
                    strict+=checked.strict(); tie+=checked.tieAware();
                }
                Artifacts.json(run.resolve("first-use/"+id+"-post-quality.json"),Map.of("queries",100,"strict",strict/100,"tie",tie/100,
                    "note","Post-first-request quality diagnostic on fixed first 100 queries; not additional first-use samples"));
            }
            end("first-"+id);
        } else {
            Inputs sub=subset(in,1000); List<Summary> rows=new ArrayList<>(); int index=0;
            for(String filterId:List.of("R-rank-1000","R-category0-rank-16000","C-rank-1000","C-rank100000-random-10000")) for(var strategy:W3Strategies.Strategy.values()) {
                var filter=filters.stream().filter(f->f.id().equals(filterId)).findFirst().orElseThrow(); int budget=-1;
                for(var p:points) if(p.get("filter").asText().equals(filterId) && p.get("strategy").asText().equals(strategy.toString())) budget=p.get("budget").asInt();
                measure(root,run,run.getFileName()+"-graph2",sub,store,built,new Cell(String.format("full-rebuild-%02d",index++),"rebuild",filterId,strategy,budget,0,1),filter,truth.get(filterId).subList(0,1000),false,rows);
            }
            Artifacts.json(run.resolve("rebuild-summary.json"),rows);
        }
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]),run=Path.of(args[1]); String mode=args[2]; W3Telemetry.start(); unit("load");
        Cell selected=mode.equals("single")?selectedCell(run,args[3]):null;
        String suffix=mode.equals("first-use")?"first-"+args[3]+"-"+args[4]+"-"+args[5]:mode;
        Artifacts.json(run.resolve("runtime/"+suffix+".json"),JVectorRuntimeInfo.capture(root.resolve(".tools/jvector-4.0.2/jvector-4.0.2-sources.jar")));
        Inputs in=load(root,run); var labels=W3Workload.readLabels(run.resolve("prepared/labels.bin"),1000000); var filters=W3Workload.filters(in.metadata(),labels);
        for(var filter:filters) {
            var manifest=Artifacts.JSON.readTree(run.resolve("prepared/gt/"+filter.id()+".manifest.json").toFile());
            if(!Artifacts.sha256(run.resolve("prepared/gt/"+filter.id()+".csv")).equals(manifest.get("sha256").asText())) throw new IllegalStateException("GT changed after validation");
        }
        long convert=System.nanoTime(); var store=new JVectorVectorStore(in.base()); long conversionNanos=System.nanoTime()-convert;
        store.verifyMapping(in.base(),1000,20261114);
        long preGcStart=System.nanoTime(); System.gc(); long preGcNanos=System.nanoTime()-preGcStart;
        end("load"); unit("build");
        try(var built=new JVectorIndexFactory().build(store,JVectorIndexFactory.BuildParameters.baseline(),null)) {
            long postGcStart=System.nanoTime(); System.gc(); long postGcNanos=System.nanoTime()-postGcStart;
            Artifacts.json(run.resolve("builds/"+suffix+".json"),Map.of("mode",mode,"structure",built.structure(),"performance",built.performance(),"conversionNanos",conversionNanos,
                "originalFloatBytes",1000000L*128*4,"convertedVectorLibraryBytes",store.libraryVectorBytes(),"preBuildGcRequestNanos",preGcNanos,"postBuildGcRequestNanos",postGcNanos));
            end("build");
            unit("prepare-ground-truth"); long truthStart=System.nanoTime();
            Map<String,List<List<Neighbor>>> truth=new HashMap<>();
            for(var filter:filters) truth.put(filter.id(),truth(run.resolve("prepared"),filter,in.queryIds()));
            Artifacts.json(run.resolve("initialization/"+suffix+".json"),Map.of("groundTruthLoadNanos",System.nanoTime()-truthStart,
                "policy","GT hashes checked before build; Neighbor objects loaded after builder closes. Explicit GC requested only during initialization, not cache eviction or query timing."));
            end("prepare-ground-truth");
            if(mode.equals("main")) { unit("bitmap-diagnostics"); bitmaps(run,in,filters); end("bitmap-diagnostics"); mainMatrix(root,run,in,filters,store,built,truth); }
            else if(mode.equals("single")) {
                var filter=filters.stream().filter(f->f.id().equals(selected.filter())).findFirst().orElseThrow();
                Artifacts.json(run.resolve("single-cell-selection.json"),Map.of("cell",selected,"buildId",run.getFileName()+"-graph1",
                    "note","One scheduled cell on a newly built graph; not merged with a sealed graph or followed by another cell."));
                measure(root,run,run.getFileName()+"-graph1",in,store,built,selected,filter,truth.get(filter.id()),false,new ArrayList<>());
            }
            else if(mode.equals("rebuild") || mode.equals("first-use")) ancillary(root,run,in,filters,store,built,truth,mode,args);
            else throw new IllegalArgumentException("Unknown mode");
        }
    }
}
