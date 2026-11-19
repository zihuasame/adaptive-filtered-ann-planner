package com.filteredann.w3;

import com.filteredann.dataset.Dataset;
import com.filteredann.evaluation.GroundTruthGenerator;
import com.filteredann.util.Artifacts;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Stream a new per-filter shard using W1 public computation, never the old writing harness. */
public final class W3GroundTruthExport {
    public static Map<String,Object> write(Path directory,Dataset base,Dataset queries,W3Workload.Filter filter) throws IOException {
        return write(directory,base,queries,filter,java.util.stream.IntStream.range(0,queries.count()).toArray());
    }
    public static Map<String,Object> write(Path directory,Dataset base,Dataset queries,W3Workload.Filter filter,int[] queryIds) throws IOException {
        if(queryIds.length!=queries.count()) throw new IllegalArgumentException("Query ID domain");
        Files.createDirectories(directory);
        Path partial=directory.resolve(filter.id()+".csv.partial"),complete=directory.resolve(filter.id()+".csv");
        if(Files.exists(complete) || Files.exists(partial)) throw new FileAlreadyExistsException("Refusing to overwrite a GT shard");
        long start=System.nanoTime(),rows=0; var generator=new GroundTruthGenerator(base);
        try(var writer=Files.newBufferedWriter(partial,StandardOpenOption.CREATE_NEW)) {
            writer.write("filter_id,query_id,rank,vector_id,squared_l2\n");
            for(int q=0;q<queries.count();q++) {
                var result=generator.filtered(queries.vector(q),filter.bitmap(),10);
                for(int rank=0;rank<result.neighbors().size();rank++) {
                    var n=result.neighbors().get(rank); writer.write(filter.id()+","+queryIds[q]+","+rank+","+n.vectorId()+","+n.squaredL2()+"\n"); rows++;
                }
            }
        }
        Files.move(partial,complete);
        return Map.of("filterId",filter.id(),"groups",queries.count(),"rows",rows,"elapsedNanos",System.nanoTime()-start,
            "sha256",Artifacts.sha256(complete),"baseCount",base.count(),"bitmapHash",W3Workload.bitmapHash(filter.bitmap()));
    }
}
