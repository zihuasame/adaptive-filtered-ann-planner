// SPDX-License-Identifier: Apache-2.0
package com.filteredann.planner.api;

import java.nio.*;
import java.security.*;
import java.util.*;

/** A bound reference. API verifies its identity and exact distances, not an arbitrary query ID. */
public record GroundTruth(String datasetId,String querySha256,String bitmapSha256,int[] ids,double[] squaredDistances,String source) {
    public GroundTruth {
        Objects.requireNonNull(datasetId);Objects.requireNonNull(querySha256);Objects.requireNonNull(bitmapSha256);Objects.requireNonNull(source);
        ids=ids.clone();squaredDistances=squaredDistances.clone();
        if(ids.length>10 || ids.length!=squaredDistances.length)throw new IllegalArgumentException("GT shape");
    }
    @Override public int[] ids(){return ids.clone();}
    @Override public double[] squaredDistances(){return squaredDistances.clone();}
    public static String queryHash(float[] query) {
        try {
            var bytes=ByteBuffer.allocate(query.length*4).order(ByteOrder.LITTLE_ENDIAN);
            for(float x:query)bytes.putInt(Float.floatToIntBits(x));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
        } catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
