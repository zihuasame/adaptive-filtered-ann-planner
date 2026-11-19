package com.filteredann.jvector;

import com.filteredann.util.Artifacts;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import java.io.*;
import java.lang.management.*;
import java.nio.file.*;
import java.util.*;

public final class JVectorRuntimeInfo {
    public static final String VERSION = "4.0.2";
    public static final String BINARY_SHA256 = "593d181d2492dec9db05a5ea14a573d72a8abc463f372a3d8e379744a60a2c15";
    public static final String SOURCES_SHA256 = "ee749a842b53cbb102817aca4447eee2db5de77b75be1389e9c40e321e7bb845";
    private JVectorRuntimeInfo() { }
    public static void requirePanama() {
        String actual = VectorizationProvider.getInstance().getClass().getName();
        if (!"panama".equals(System.getProperty("jvector.vectorization_provider"))
                || !actual.equals("io.github.jbellis.jvector.vector.PanamaVectorizationProvider")) {
            throw new IllegalStateException("Explicit Panama required; actual=" + actual);
        }
    }
    public static Map<String,Object> capture(Path sourcesJar) throws Exception {
        requirePanama();
        var provider = VectorizationProvider.getInstance();
        Path binary = Path.of(VectorizationProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String binaryHash = Artifacts.sha256(binary), sourceHash = Artifacts.sha256(sourcesJar);
        if (!binaryHash.equals(BINARY_SHA256) || !sourceHash.equals(SOURCES_SHA256)) throw new IOException("JVector release JAR checksum mismatch");
        Map<String,Object> info = new LinkedHashMap<>();
        info.put("jvectorVersion",VERSION); info.put("binaryJarSha256",binaryHash); info.put("sourcesJarSha256",sourceHash);
        info.put("provider",provider.getClass().getName()); info.put("vectorUtil",provider.getVectorUtilSupport().getClass().getName());
        info.put("vectorTypeSupport",provider.getVectorTypeSupport().getClass().getName());
        Object species = Class.forName("jdk.incubator.vector.FloatVector").getField("SPECIES_PREFERRED").get(null);
        info.put("preferredFloatSpecies",species.toString());
        info.put("javaVersion",System.getProperty("java.version")); info.put("javaVendor",System.getProperty("java.vendor"));
        info.put("osName",System.getProperty("os.name")); info.put("osVersion",System.getProperty("os.version")); info.put("osArch",System.getProperty("os.arch"));
        info.put("availableProcessors",Runtime.getRuntime().availableProcessors());
        info.put("jvmArguments",ManagementFactory.getRuntimeMXBean().getInputArguments());
        info.put("physicalMemoryBytes",osBean().getTotalMemorySize()); info.put("freePhysicalMemoryBytes",osBean().getFreeMemorySize());
        return info;
    }
    public static com.sun.management.OperatingSystemMXBean osBean() {
        return (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    }
}
