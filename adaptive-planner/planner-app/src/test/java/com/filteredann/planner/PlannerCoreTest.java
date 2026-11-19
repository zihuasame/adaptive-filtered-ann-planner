// SPDX-License-Identifier: Apache-2.0
package com.filteredann.planner;

import com.filteredann.filter.FilterBitmap;
import com.filteredann.planner.catalog.PerformanceCatalog;
import com.filteredann.planner.core.Planner;
import com.filteredann.planner.engine.FourStrategyEngine.Strategy;
import com.filteredann.planner.filter.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;

class PlannerCoreTest {
    static PerformanceCatalog catalog;static Path file;
    @BeforeAll static void load()throws Exception {file=Path.of(System.getProperty("planner.catalog"));catalog=new PerformanceCatalog(file);}
    static FilterEngine.Prepared evidence(String id,FilterEngine.CacheState state)throws Exception {
        var f=catalog.find(FilterSpec.known(id)).orElseThrow();
        byte[] bytes=Files.readAllBytes(file.resolveSibling("bitmaps").resolve(id+".bitmap"));
        var mask=FilterBitmap.of(250000,BitSet.valueOf(bytes).stream().toArray());
        assertEquals(f.matches(),mask.cardinality());assertEquals(f.bitmapSha256(),FilterEngine.hash(mask));
        return new FilterEngine.Prepared(f.spec(),mask,f.bitmapSha256(),f.matches(),f.selectivity(),state,0);
    }
    Planner planner(boolean correctData) {return new Planner(catalog,new Planner.Context(catalog.dataset().id(),250000,128,correctData,"product-test-new-build"));}
    @Test void realCatalogChoosesNForBroadRandomAndEForSparseAndCorrelated()throws Exception {
        assertEquals(Strategy.N,planner(true).plan(evidence("R-rank-100000",FilterEngine.CacheState.HOT),.95,null).strategy());
        assertEquals(Strategy.E,planner(true).plan(evidence("R-rank-1000",FilterEngine.CacheState.HOT),.95,null).strategy());
        assertEquals(Strategy.E,planner(true).plan(evidence("C-rank-100000",FilterEngine.CacheState.HOT),.95,null).strategy());
    }
    @Test void realCatalogCanChoosePAndOAtExplicitLowerTargets()throws Exception {
        var filter=evidence("R-rank-100000",FilterEngine.CacheState.HOT);
        assertEquals(Strategy.P,planner(true).plan(filter,.05,null).strategy());
        var o=planner(true).plan(filter,.20,null);assertEquals(Strategy.O,o.strategy());assertEquals(40,o.budget());
    }
    @Test void budgetCapNeverInventsOrSilentlyLowersQuality()throws Exception {
        var filter=evidence("R-rank-100000",FilterEngine.CacheState.HOT);
        var limited=planner(true).plan(filter,.999,20);
        assertEquals(Strategy.E,limited.strategy());assertEquals(0,limited.budget());
        assertTrue(limited.alternatives().stream().anyMatch(a->a.status().equals("BUDGET_CAP")));
    }
    @Test void coldDataMismatchAndExactRequirementAreExplicit()throws Exception {
        var cold=planner(true).plan(evidence("R-rank-100000",FilterEngine.CacheState.COLD),.95,null);
        assertEquals(Strategy.E,cold.strategy());assertTrue(cold.safeFallback());assertNull(cold.currentBuildPredictedMs());
        assertTrue(planner(false).plan(evidence("R-rank-100000",FilterEngine.CacheState.HOT),.95,null).safeFallback());
        assertEquals(Strategy.E,planner(true).plan(evidence("R-rank-100000",FilterEngine.CacheState.HOT),1,null).strategy());
    }
    @Test void allEightMetadataHashesMatchTheFrozenPackedBitCodec()throws Exception {
        for(var f:catalog.filters())evidence(f.id(),FilterEngine.CacheState.HOT);
        var b=FilterBitmap.of(9,0,7,8);
        var expected=java.security.MessageDigest.getInstance("SHA-256").digest(new byte[]{(byte)0x81,1});
        assertEquals(HexFormat.of().formatHex(expected),FilterEngine.hash(b));
    }
    @Test void knownNameCannotSubstituteForAnUnknownPredicate() {
        var spec=FilterSpec.equalTo(FilterSpec.Field.REGION,1);var b=FilterBitmap.of(250000,1,3,7);
        var prepared=new FilterEngine.Prepared(spec,b,FilterEngine.hash(b),3,3.0/250000,FilterEngine.CacheState.HOT,0);
        var decision=planner(true).plan(prepared,.95,null);assertEquals(Strategy.E,decision.strategy());assertFalse(decision.supportedByHistoricalEvidence());
    }
    @Test void alteredPerformanceEvidenceCannotBeLoaded(@org.junit.jupiter.api.io.TempDir Path dir)throws Exception {
        Files.copy(file.resolveSibling("source-manifest.json"),dir.resolve("source-manifest.json"));
        Files.writeString(dir.resolve("catalog.json"),Files.readString(file)+" ");
        assertThrows(java.io.IOException.class,()->new PerformanceCatalog(dir.resolve("catalog.json")));
    }
}
