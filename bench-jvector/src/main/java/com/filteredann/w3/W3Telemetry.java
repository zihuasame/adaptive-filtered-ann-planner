package com.filteredann.w3;

import java.lang.management.ManagementFactory;

/** Sampling runs outside query threads; Windows supervisor records process/system bytes. */
public final class W3Telemetry {
    public static void start() {
        Thread thread=new Thread(()->{
            try {
                while(true) {
                    var heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                    System.out.println("W3MEM,"+System.currentTimeMillis()+","+heap.getUsed()+","+heap.getMax());
                    Thread.sleep(250);
                }
            } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
        },"w3-telemetry");
        thread.setDaemon(true); thread.start();
    }
    public static void main(String[] args) throws Exception {
        start();
        for(int i=0;i<40;i++) { byte[] allocation=new byte[1024*1024]; allocation[0]=1; Thread.sleep(250); }
    }
}
