package com.filteredann.w3;

import com.filteredann.dataset.Dataset;
import com.filteredann.metadata.MetadataGenerator;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class W3FullRunnerTest {
    @Test void singleCellSelectionUsesScheduleAndRejectsUnknown(@org.junit.jupiter.api.io.TempDir java.nio.file.Path run) throws Exception {
        java.nio.file.Files.writeString(run.resolve("schedule-plan.json"),"[{\"id\":\"full-hot-0036\",\"filter\":\"C-rank-10000\",\"strategy\":\"O\",\"budget\":320,\"repeat\":0,\"threads\":8}]");
        var cell=W3FullRunner.selectedCell(run,"full-hot-0036");
        assertEquals("C-rank-10000",cell.filter()); assertEquals(W3Strategies.Strategy.O,cell.strategy());
        assertEquals(320,cell.budget()); assertEquals(8,cell.threads()); assertEquals(0,cell.repeat());
        assertThrows(IllegalArgumentException.class,()->W3FullRunner.selectedCell(run,"full-hot-0035"));
    }
    static W3MinimalRunner.Summary summary(int repeat,double strict) {
        return new W3MinimalRunner.Summary("cell-"+repeat,"reuse","filter","N",40,repeat,1,10000,1000000,strict,strict,0,0,100,100,100,100,100,10000,1,1,1,0,0);
    }
    @Test void allThreeRoundsRequiredAndBudgetIsNotInterchangeable() {
        assertFalse(W3FullRunner.meets(List.of(summary(0,1),summary(1,1)),"filter",W3Strategies.Strategy.N,40,1,.99));
        assertFalse(W3FullRunner.meets(List.of(summary(0,1),summary(1,.98),summary(2,1)),"filter",W3Strategies.Strategy.N,40,1,.99));
        assertTrue(W3FullRunner.meets(List.of(summary(0,1),summary(1,1),summary(2,1)),"filter",W3Strategies.Strategy.N,40,1,.99));
        assertFalse(W3FullRunner.meets(List.of(summary(0,1),summary(1,1),summary(2,1)),"filter",W3Strategies.Strategy.N,80,1,.99));
        assertFalse(W3FullRunner.meets(List.of(summary(0,1),summary(0,1),summary(0,1)),"filter",W3Strategies.Strategy.N,40,1,.99));
    }
    @Test void subsetPreservesOriginalQueryIdentityAndOrder() {
        float[] rows=new float[3*128]; Arrays.fill(rows,128,256,2); Arrays.fill(rows,256,384,3);
        var in=new W3MinimalRunner.Inputs(new Dataset(new float[16*128],128),new Dataset(rows,128),new MetadataGenerator().generate(16,20261113),new int[]{9000,5,777});
        var subset=W3FullRunner.subset(in,2); assertArrayEquals(new int[]{9000,5},subset.queryIds()); assertEquals(2,subset.queries().count());
        assertArrayEquals(in.queries().vector(1),subset.queries().vector(1)); assertSame(in.base(),subset.base());
    }
}
