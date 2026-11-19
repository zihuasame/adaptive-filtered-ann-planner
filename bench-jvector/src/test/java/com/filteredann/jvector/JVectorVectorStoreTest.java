package com.filteredann.jvector;

import com.filteredann.dataset.Dataset;
import io.github.jbellis.jvector.vector.*;
import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class JVectorVectorStoreTest {
    @Test void dependencyAndExplicitPanamaAreLoaded() throws Exception {
        JVectorRuntimeInfo.requirePanama();
        assertEquals("io.github.jbellis.jvector.vector.PanamaVectorUtilSupport",VectorizationProvider.getInstance().getVectorUtilSupport().getClass().getName());
        var jar=java.nio.file.Path.of(VectorizationProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertEquals(JVectorRuntimeInfo.BINARY_SHA256,com.filteredann.util.Artifacts.sha256(jar));
    }
    @Test void copiesRowsWithIdentityMappingAndStableRandomAccess() {
        float[] values=new float[1000*128]; Random random=new Random(12);
        for(int i=0;i<values.length;i++) values[i]=random.nextFloat();
        Dataset source=new Dataset(values,128); JVectorVectorStore store=new JVectorVectorStore(source);
        assertEquals(1000,store.count()); assertEquals(128,store.dimensions());
        store.verifyMapping(source,1000,20261113L);
        assertFalse(store.values().isValueShared()); assertSame(store.values(),store.values().copy());
        assertEquals(512000,store.floatPayloadBytes()); assertTrue(store.libraryVectorBytes()>=store.floatPayloadBytes());
        float first=store.values().getVector(0).get(0); values[0]=999;
        assertEquals(first,store.values().getVector(0).get(0));
        assertThrows(IllegalArgumentException.class,()->store.verifyMapping(source,1001,1));
    }
}
