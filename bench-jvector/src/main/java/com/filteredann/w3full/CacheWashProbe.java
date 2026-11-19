package com.filteredann.w3full;

import com.filteredann.w3.W3Telemetry;
import com.filteredann.util.Artifacts;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Capability positive control only: never an ANN result or proof that a graph is wholly cold. */
public final class CacheWashProbe {
    static volatile long sink;
    static long walk(int[] next) { int id=0; long sum=0; for(int i=0;i<next.length;i++) { id=next[id]; sum+=id; } sink=sum; return sum; }
    static void wash(byte[] data) {
        int mask=(data.length>>>6)-1,line=0; long sum=0;
        for(int i=0;i<=mask;i++) { line=(line*1664525+1013904223)&mask; sum+=data[line<<6]; }
        sink=sum;
    }
    static long epochNanos() { Instant now=Instant.now(); return now.getEpochSecond()*1000000000L+now.getNano(); }
    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[0]); Files.createDirectory(out.resolve("measurements")); W3Telemetry.start();
        System.out.println("W3UNIT,START,cache-wash-positive-control");
        long deadline=System.nanoTime()+30_000_000_000L;
        while(!Files.exists(out.resolve("go.flag"))) { if(System.nanoTime()>deadline) throw new IllegalStateException("Affinity handshake missing"); Thread.sleep(25); }
        int n=1<<21; int[] order=new int[n],next=new int[n]; for(int i=0;i<n;i++) order[i]=i;
        Random random=new Random(20261114);
        for(int i=n-1;i>0;i--) { int j=random.nextInt(i+1),temp=order[i]; order[i]=order[j]; order[j]=temp; }
        for(int i=0;i<n;i++) next[order[i]]=order[(i+1)&(n-1)];
        byte[] pressure=new byte[256*1024*1024]; for(int i=0;i<pressure.length;i+=64) pressure[i]=(byte)((i>>>6)*31+1);
        for(int warm=0;warm<40;warm++) walk(next);
        List<Map<String,Object>> rows=new ArrayList<>(); long expected=(long)n*(n-1)/2;
        for(int pair=0;pair<30;pair++) {
            boolean washFirst=random.nextBoolean();
            for(int position=0;position<2;position++) {
                boolean evict=position==0?washFirst:!washFirst; walk(next);
                if(evict) wash(pressure);
                long epochStart=epochNanos(),start=System.nanoTime(),checksum=walk(next),nanos=System.nanoTime()-start,epochEnd=epochNanos();
                if(checksum!=expected) throw new AssertionError("Pointer cycle checksum mismatch");
                rows.add(Map.of("pair",pair,"position",position,"condition",evict?"capacity-wash":"warm-control","epochStartNs",epochStart,"epochEndNs",epochEnd,"elapsedNs",nanos,"checksum",checksum));
            }
        }
        Artifacts.json(out.resolve("measurements/requests.json"),rows);
        Artifacts.json(out.resolve("measurements/protocol.json"),Map.of("scope","synthetic-cache-wash-capability-only","targetBytes",n*4,"washBytes",pressure.length,"pairs",30,
            "seed",20261114,"warmups",40,"strideBytes",64,"allGraphColdProven",false,"hardwareCountersValidated",false,"javaVersion",System.getProperty("java.version")));
        System.out.println("W3UNIT,END,cache-wash-positive-control");
    }
}
