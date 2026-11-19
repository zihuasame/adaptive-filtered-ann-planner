package com.filteredann.w3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class W3Phase0Test {
    @TempDir Path temp;
    @Test void publicProbeCompilesAndRunsWithinExistingModule() throws Exception {
        Path root=Path.of(System.getProperty("user.dir")).getParent();
        W3Phase0Probe.main(new String[]{root.toString(),temp.toString()});
        assertTrue(java.nio.file.Files.readString(temp.resolve("probe-status.json")).contains("true"));
    }
}
