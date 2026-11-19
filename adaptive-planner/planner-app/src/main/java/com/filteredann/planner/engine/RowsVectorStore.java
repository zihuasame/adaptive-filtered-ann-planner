// SPDX-License-Identifier: Apache-2.0
package com.filteredann.planner.engine;

import io.github.jbellis.jvector.graph.RandomAccessVectorValues;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Recovery Rows storage pattern: one owned float row, no continuous duplicate payload. */
public final class RowsVectorStore implements RandomAccessVectorValues {
    private final List<VectorFloat<?>> rows;
    private final int dimension;
    private RowsVectorStore(List<VectorFloat<?>> rows,int dimension) { this.rows=List.copyOf(rows);this.dimension=dimension; }
    public static RowsVectorStore read(Path file,int n,int dimension) throws IOException {
        long rowBytes=Math.multiplyExact(4L,dimension+1L);
        if(n<=0 || dimension<=0 || Files.size(file)%rowBytes!=0 || Files.size(file)<n*rowBytes)throw new IOException("Invalid fvecs shape/prefix");
        var support=VectorizationProvider.getInstance().getVectorTypeSupport();
        List<VectorFloat<?>> rows=new ArrayList<>(n);byte[] bytes=new byte[Math.toIntExact(rowBytes)];
        try(var in=new BufferedInputStream(Files.newInputStream(file),1<<20)) {
            for(int id=0;id<n;id++) {
                if(in.readNBytes(bytes,0,bytes.length)!=bytes.length)throw new EOFException("Vector "+id);
                var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                if(b.getInt()!=dimension)throw new IOException("Vector dimension at "+id);
                float[] row=new float[dimension];
                for(int d=0;d<dimension;d++){row[d]=b.getFloat();if(!Float.isFinite(row[d]))throw new IOException("Nonfinite vector "+id);}
                rows.add(support.createFloatVector(row));
            }
        }
        return new RowsVectorStore(rows,dimension);
    }
    public static RowsVectorStore of(float[][] vectors) {
        if(vectors.length==0 || vectors[0].length==0)throw new IllegalArgumentException("Empty vectors");
        int d=vectors[0].length;List<VectorFloat<?>> rows=new ArrayList<>();
        for(float[] vector:vectors){if(vector.length!=d)throw new IllegalArgumentException("Dimensions");for(float x:vector)if(!Float.isFinite(x))throw new IllegalArgumentException("Nonfinite");rows.add(VectorizationProvider.getInstance().getVectorTypeSupport().createFloatVector(vector.clone()));}
        return new RowsVectorStore(rows,d);
    }
    @Override public int size(){return rows.size();}
    @Override public int dimension(){return dimension;}
    @Override public VectorFloat<?> getVector(int id){return rows.get(id);}
    @Override public boolean isValueShared(){return false;}
    @Override public RowsVectorStore copy(){return this;}
    public int count(){return size();}
    public int dimensions(){return dimension;}
    public RandomAccessVectorValues values(){return this;}
    public float[] vector(int id){float[] q=new float[dimension];for(int d=0;d<dimension;d++)q[d]=rows.get(id).get(d);return q;}
    public double squaredL2(float[] query,int id){
        if(query.length!=dimension)throw new IllegalArgumentException("Query dimensions");
        double sum=0;var row=rows.get(id);for(int d=0;d<dimension;d++){double delta=(double)query[d]-row.get(d);sum+=delta*delta;}return sum;
    }
}
