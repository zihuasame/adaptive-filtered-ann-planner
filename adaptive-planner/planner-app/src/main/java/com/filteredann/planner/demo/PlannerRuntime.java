// SPDX-License-Identifier: Apache-2.0
package com.filteredann.planner.demo;

import com.fasterxml.jackson.databind.*;
import com.filteredann.jvector.JVectorRuntimeInfo;
import com.filteredann.metadata.*;
import com.filteredann.planner.api.*;
import com.filteredann.planner.catalog.PerformanceCatalog;
import com.filteredann.planner.core.Planner;
import com.filteredann.planner.engine.*;
import com.filteredann.planner.filter.*;
import com.filteredann.util.Artifacts;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Dataset loading and lifecycle of one in-memory graph. */
public final class PlannerRuntime implements AutoCloseable {
    private final RowsVectorStore vectors;
    private final ProductGraphFactory.BuiltIndex built;
    private final PerformanceCatalog catalog;
    private final FilterEngine filters;
    private final Planner.Context context;
    private final Path root;
    private final boolean synthetic;
    private final Map<String,String> verifiedTruths=new java.util.concurrent.ConcurrentHashMap<>();
    private PlannerRuntime(Path root,boolean synthetic,RowsVectorStore vectors,MetadataStore metadata,int[] clusterRanks,PerformanceCatalog catalog,boolean verified)throws Exception {
        this.root=root;this.synthetic=synthetic;this.vectors=vectors;this.catalog=catalog;
        filters=new FilterEngine(metadata,clusterRanks,vectors.count(),64);
        long start=System.nanoTime();built=new ProductGraphFactory().build(vectors,ProductGraphFactory.BuildParameters.baseline(),null);
        String buildId="product-demo-"+UUID.randomUUID();
        context=new Planner.Context(verified?catalog.dataset().id():"uncalibrated-product-fixture",vectors.count(),vectors.dimensions(),verified,buildId);
        System.out.printf(Locale.ROOT,"PRODUCT_READY nodes=%d dimensions=%d graphBuildMs=%.3f buildId=%s provider=%s historicalTransfer=%s%n",vectors.count(),vectors.dimensions(),(System.nanoTime()-start)/1e6,buildId,io.github.jbellis.jvector.vector.VectorizationProvider.getInstance().getClass().getSimpleName(),verified);
    }
    public static PlannerRuntime open(Path root,int count,boolean synthetic)throws Exception {
        JVectorRuntimeInfo.requirePanama();
        if(Runtime.version().feature()!=21)throw new IllegalStateException("Java 21 required");
        var catalog=new PerformanceCatalog(root.resolve("adaptive-planner/performance-catalog/catalog-v0.1.json"));
        if(synthetic) {
            Random random=new Random(20261119);float[][] data=new float[1024][8];
            for(var row:data)for(int d=0;d<row.length;d++)row[d]=random.nextFloat()*100;
            var metadata=new MetadataGenerator().generate(data.length,20261113);int[] ranks=new int[data.length];for(int id=0;id<ranks.length;id++)ranks[id]=id;
            return new PlannerRuntime(root,true,RowsVectorStore.of(data),metadata,ranks,catalog,false);
        }
        if(count<1000 || count>250000)throw new IllegalArgumentException("Supported node count: 1000..250000");
        if(JVectorRuntimeInfo.osBean().getFreeMemorySize()<2L*1024*1024*1024)throw new IllegalStateException("Product graph startup needs 2 GiB available; no automatic retry");
        Path base=root.resolve("data/raw/sift_base.fvecs"),meta=root.resolve("data/processed/metadata/sift1m-random-v1.bin"),labels=root.resolve("adaptive-planner/data/labels.bin");
        if(!Files.isRegularFile(labels))labels=root.resolve("results/w3-r-ef-final002/postprocess-20261119T050558/engineering-20261119T002913/labels.bin");
        for(Path p:List.of(base,meta,labels))if(!Files.isRegularFile(p))throw new IOException("Missing "+root.relativize(p)+"; use --synthetic or prepare SIFT assets");
        boolean identity=Artifacts.sha256(base).equals(catalog.dataset().baseSha256())&&Artifacts.sha256(meta).equals(catalog.dataset().metadataSha256())&&Artifacts.sha256(labels).equals(catalog.dataset().labelsSha256());
        if(!identity)throw new IOException("Dataset/metadata/labels differ from the historical catalog; do not transfer SIFT priors");
        var metadata=new MetadataGenerator().read(meta,20261113);int[] ranks=readClusterRanks(labels);
        return new PlannerRuntime(root,false,RowsVectorStore.read(base,count,128),metadata,ranks,catalog,count==catalog.dataset().count());
    }
    private static int[] readClusterRanks(Path path)throws IOException {
        try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if(!in.readUTF().equals("W3CLUSTER1"))throw new IOException("Labels header");
            int n=in.readInt();if(n!=1000000)throw new IOException("Labels domain");
            for(int i=0;i<32;i++)in.readInt();int[] ranks=new int[n];
            for(int id=0;id<n;id++){in.readInt();ranks[id]=in.readInt();if(!Double.isFinite(in.readDouble()))throw new IOException("Labels distance");}
            if(in.read()!=-1)throw new IOException("Trailing label bytes");return ranks;
        }
    }
    public QueryApi newSession(){return new QueryApi(vectors,built.graph(),filters,catalog,context);}
    public RowsVectorStore vectorStore(){return vectors;}
    public FilterEngine.Prepared prepare(FilterSpec spec){return filters.acquire(spec,false);}
    public PerformanceCatalog catalog(){return catalog;}
    public Planner.Context context(){return context;}
    public float[] queryVector(int id)throws Exception {
        if(synthetic)return vectors.vector(Objects.checkIndex(id,vectors.count()));
        if(id<0||id>=10000)throw new IllegalArgumentException("SIFT query id must be 0..9999");
        Path file=root.resolve("data/raw/sift_query.fvecs");
        // Verify query corpus once, before any query ID may be used to bind GT.
        if(!verifiedTruths.containsKey("QUERY_CORPUS")) {
            if(!Artifacts.sha256(file).equals(catalog.dataset().querySha256()))throw new IOException("Query dataset checksum mismatch");
            verifiedTruths.put("QUERY_CORPUS",catalog.dataset().querySha256());
        }
        try(var channel=FileChannel.open(file,StandardOpenOption.READ)) {
            var b=ByteBuffer.allocate(516).order(ByteOrder.LITTLE_ENDIAN);readFully(channel,b,id*516L);b.flip();
            if(b.getInt()!=128)throw new IOException("Query dimension");float[] q=new float[128];for(int d=0;d<128;d++)q[d]=b.getFloat();return q;
        }
    }
    public GroundTruth truth(FilterSpec spec,int queryId,float[] query)throws Exception {
        if(synthetic || !context.verifiedCatalogDataset())return null;
        var evidence=catalog.find(spec).orElse(null);if(evidence==null)return null;
        if(!Arrays.equals(queryVector(queryId),query))throw new IllegalArgumentException("Query ID does not bind the supplied vector to GT");
        Path dir=root.resolve("adaptive-planner/data/engineering-gt");
        if(!Files.isRegularFile(dir.resolve("validation-summary.json")))dir=root.resolve("results/w3-r-ef-final002/postprocess-20261119T050558/engineering-20261119T002913/engineering-gt");
        if(!Files.exists(dir.resolve("validation-summary.json")))return null;
        var summary=new ObjectMapper().readTree(dir.resolve("validation-summary.json").toFile());
        if(!summary.path("state").asText().equals("COMPLETED")||summary.path("groups").asInt()!=80000||summary.path("errors").asInt()!=0)throw new IOException("Unverified engineering GT");
        String hash=null;for(var f:summary.required("filters"))if(f.path("filter").asText().equals(evidence.id()))hash=f.required("compactSha256").asText();
        if(hash==null)throw new IOException("GT filter missing");Path path=dir.resolve(evidence.id()+".gtbin");
        if(!Files.isRegularFile(path))return null;
        if(!hash.equals(verifiedTruths.get(evidence.id()))) {
            if(Files.size(path)!=10000*120L || !Artifacts.sha256(path).equals(hash))throw new IOException("GT length/hash mismatch");verifiedTruths.put(evidence.id(),hash);
        }
        // Recovery DiskTruth pattern: only ten primitive records per query; no full GT JSON tree.
        try(var channel=FileChannel.open(path,StandardOpenOption.READ)) {
            var b=ByteBuffer.allocate(120).order(ByteOrder.LITTLE_ENDIAN);readFully(channel,b,queryId*120L);b.flip();
            int[] ids=new int[10];double[] distances=new double[10];for(int i=0;i<10;i++){ids[i]=b.getInt();distances[i]=b.getDouble();}
            return new GroundTruth(context.datasetId(),GroundTruth.queryHash(query),evidence.bitmapSha256(),ids,distances,"verified-prefix-DiskTruth:"+evidence.id()+":"+queryId);
        }
    }
    private static void readFully(FileChannel channel,ByteBuffer b,long offset)throws IOException {
        while(b.hasRemaining()){int n=channel.read(b,offset);if(n<=0)throw new EOFException("No read progress");offset+=n;}
    }
    @Override public void close()throws Exception{built.close();}
}
