package org.boxtheta.javapower.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.SplittableRandom;
import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.DecodeTable;
import org.boxtheta.javapower.core.PhysicalMemory;
import org.boxtheta.javapower.core.StateInput;
import org.boxtheta.javapower.core.StateMismatchException;
import org.boxtheta.javapower.core.StateOutput;
import org.junit.jupiter.api.Test;

/** Saving and loading the CPUs of a domain: timebase, decrementer, darn. */
class CpuStateSaveTest {
    @Test
    void darnKeepsTheSplittableRandomSequence() {
        for (long seed : new long[] {0, 1, -1, 0x123456789abcdefL}) {
            SplittableRandom r = new SplittableRandom(seed);
            DarnRandom d = new DarnRandom(seed);
            for (int i = 0; i < 1000; i++) {
                assertEquals(r.nextLong(), d.nextLong());
            }
        }
    }

    private static Interpreter cpu(CpuDomain d) {
        DecodeTable t = new DecodeTable();
        t.addAll(new IntInsns());
        CpuState s = new CpuState();
        return new Interpreter(s, d, t);
    }

    private static byte[] save(CpuDomain d) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        StateOutput out = new StateOutput(bo);
        d.saveState(out);
        out.finish();
        return bo.toByteArray();
    }

    private static void load(CpuDomain d, byte[] b) throws IOException {
        StateInput in = new StateInput(new ByteArrayInputStream(b));
        d.loadState(in);
        in.finish();
    }

    @Test
    void deterministicTimeAndDecrementerContinueExactly() throws IOException {
        PhysicalMemory m = new PhysicalMemory(1 << 20);
        CpuDomain d = new CpuDomain(m, Interpreter.Clock.DETERMINISTIC);
        Interpreter a = cpu(d);
        a.icountShift = 3;
        a.state().icount = 1_000_000;
        a.storeDec(5000);
        a.random.nextLong();
        byte[] b = save(d);

        CpuDomain d2 = new CpuDomain(new PhysicalMemory(1 << 20), Interpreter.Clock.DETERMINISTIC, 1.0, 99);
        Interpreter c = cpu(d2);
        c.icountShift = 3;
        load(d2, b);
        assertEquals(a.tb(), c.tb());
        assertEquals(a.loadDec(), c.loadDec());
        assertEquals(a.random.nextLong(), c.random.nextLong());
        a.state().icount += 10_000;
        c.state().icount += 10_000;
        assertEquals(a.tb(), c.tb());
        assertEquals(a.decPending(), c.decPending());
        assertTrue(c.decPending());
    }

    @Test
    void wallClockResumesWhereItWasSaved() throws Exception {
        CpuDomain d = new CpuDomain(new PhysicalMemory(1 << 20), Interpreter.Clock.WALL);
        Interpreter a = cpu(d);
        Interpreter a1 = cpu(d);
        a.storeDec(Interpreter.TB_FREQ); // one second
        Thread.sleep(20);
        long tb = a.tb();
        byte[] b = save(d);
        Thread.sleep(200); // time the machine spends saved does not reach the guest

        CpuDomain d2 = new CpuDomain(new PhysicalMemory(1 << 20), Interpreter.Clock.WALL, 2.0);
        Interpreter c = cpu(d2);
        Interpreter c1 = cpu(d2);
        load(d2, b);
        long tbc = c.tb();
        assertTrue(tbc >= tb && tbc - tb < Interpreter.TB_FREQ / 20, tb + " -> " + tbc);
        assertTrue(Math.abs(c1.tb() - c.tb()) < Interpreter.TB_FREQ / 100);
        long dec = c.loadDec() & 0xffffffffL;
        assertTrue(dec > Interpreter.TB_FREQ * 9 / 10 && dec <= Interpreter.TB_FREQ, "dec " + dec);

        CpuDomain one = new CpuDomain(new PhysicalMemory(1 << 20), Interpreter.Clock.WALL);
        cpu(one);
        StateInput in = new StateInput(new ByteArrayInputStream(b));
        StateMismatchException e = assertThrows(StateMismatchException.class, () -> one.loadState(in));
        assertTrue(e.getMessage().contains("2 CPUs"), e.getMessage());
        assertEquals(a1.state().index, c1.state().index);
    }

    @Test
    void stateSavedUnderTheWallClockLoadsDeterministically() throws IOException {
        CpuDomain d = new CpuDomain(new PhysicalMemory(1 << 20), Interpreter.Clock.WALL);
        Interpreter a = cpu(d);
        a.storeDec(Interpreter.TB_FREQ / 100); // 10 ms
        long tb = a.tb();
        byte[] b = save(d);
        CpuDomain d2 = new CpuDomain(new PhysicalMemory(1 << 20), Interpreter.Clock.DETERMINISTIC);
        Interpreter c = cpu(d2);
        load(d2, b);
        assertTrue(c.tb() >= tb && c.tb() - tb < Interpreter.TB_FREQ / 10);
        long dec = c.loadDec();
        assertTrue(dec > 0 && dec <= Interpreter.TB_FREQ / 100, "dec " + dec);
        assertTrue(!c.decPending());
        c.state().icount += 20_000_000; // 20 ms at 1 ns per instruction
        assertTrue(c.decPending());
    }
}
