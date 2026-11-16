package org.boxtheta.javapower.fpvec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The VMX and VSX load and store handlers against the reference ({@link VecMemDiff}) on a real
 * MMU, with a fixed seed. {@link VecMemSoak} runs the same check for longer.
 */
class VecMemDiffTest {
    static final int CASES = 3000;

    @Test
    void sameAsReference() {
        VecMemDiff d = new VecMemDiff(0x5eed_10ad_57_0eL);
        d.runAll(CASES);
        System.out.println(d.summary());
        assertEquals(0, d.mismatches, d.summary() + d.report);
        d.counts.forEach((name, c) -> {
            assertTrue(c[0] == CASES, name + " ran " + c[0] + " cases");
            // Every form faulted sometimes, and mostly did not (lvsl and lvsr make no access).
            if (!name.startsWith("lvs")) {
                assertTrue(c[2] > CASES / 50 && c[2] < CASES / 2, name + ": " + c[2] + " faults");
            }
        });
    }
}
