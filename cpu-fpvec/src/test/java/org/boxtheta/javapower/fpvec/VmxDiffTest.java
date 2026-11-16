package org.boxtheta.javapower.fpvec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The lane-arithmetic VMX handlers against the element-by-element reference ({@link VmxDiff}),
 * with a fixed seed. {@link VmxSoak} runs the same check for longer.
 */
class VmxDiffTest {
    static final int CASES = 6000;

    @Test
    void sameAsReference() {
        VmxDiff d = new VmxDiff(0x5eed_1e55_0f_7ec5L);
        d.runAll(CASES);
        assertEquals(0, d.mismatches, d.mismatches + " mismatches in " + d.cases + " cases:" + d.report);
        // Every instruction ran: no pattern is shadowed by a more specific one everywhere.
        d.counts.forEach((name, c) -> assertTrue(c[0] > CASES / 2, name + " ran " + c[0] + " cases"));
    }
}
