package org.boxtheta.javapower.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The record framing of saved states, guest RAM images and CPU state. */
class StateFormatTest {
    private static byte[] sample() throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        StateOutput out = new StateOutput(bo);
        out.record("ONE ", d -> {
            d.writeInt(42);
            StateOutput.writeString(d, "hello");
            StateOutput.writeString(d, null);
            StateOutput.writeBytes(d, new byte[] {1, 2, 3});
            StateOutput.writeBytes(d, null);
        });
        out.record("TWO ", d -> d.writeLong(-7));
        out.finish();
        return bo.toByteArray();
    }

    private static void readSample(byte[] b) throws IOException {
        StateInput in = new StateInput(new ByteArrayInputStream(b));
        StateInput.Record r = in.record("ONE ");
        assertEquals(42, r.readInt());
        assertEquals("hello", r.readString());
        assertNull(r.readString());
        assertArrayEquals(new byte[] {1, 2, 3}, r.readBytes(16));
        assertNull(r.readBytes(16));
        assertEquals(-7, in.record("TWO ").readLong());
        in.finish();
    }

    @Test
    void recordsRoundTrip() throws IOException {
        readSample(sample());
    }

    @Test
    void foreignInputAndOtherVersionsAreRefused() throws IOException {
        StateFormatException e = assertThrows(StateFormatException.class,
                () -> new StateInput(new ByteArrayInputStream("definitely not a state".getBytes())));
        assertTrue(e.getMessage().contains("not a javapower machine state"), e.getMessage());
        byte[] b = sample();
        b[11] = 2; // format version 2
        e = assertThrows(StateFormatException.class, () -> readSample(b));
        assertTrue(e.getMessage().contains("format version 2") && e.getMessage().contains("version 1"),
                e.getMessage());
    }

    /** Every prefix of a state is refused as truncated, never misread. */
    @Test
    void everyTruncationIsRefused() throws IOException {
        byte[] b = sample();
        for (int n = 0; n < b.length; n++) {
            byte[] cut = java.util.Arrays.copyOf(b, n);
            assertThrows(StateFormatException.class, () -> readSample(cut), "prefix of " + n + " bytes");
        }
    }

    /** Flipping any bit of a state is detected (header, framing or checksum). */
    @Test
    void everyBitFlipIsRefused() throws IOException {
        byte[] b = sample();
        for (int i = 0; i < b.length; i++) {
            for (int bit = 0; bit < 8; bit++) {
                byte[] c = b.clone();
                c[i] ^= (byte) (1 << bit);
                assertThrows(StateFormatException.class, () -> readSample(c), "byte " + i + " bit " + bit);
            }
        }
    }

    @Test
    void recordsMustComeInOrderAndBeReadWhole() throws IOException {
        byte[] b = sample();
        StateInput in = new StateInput(new ByteArrayInputStream(b));
        StateFormatException e = assertThrows(StateFormatException.class, () -> in.record("TWO "));
        assertTrue(e.getMessage().contains("expected record TWO , found ONE "), e.getMessage());

        StateInput in2 = new StateInput(new ByteArrayInputStream(b));
        in2.record("ONE ").readInt();
        e = assertThrows(StateFormatException.class, () -> in2.record("TWO "));
        assertTrue(e.getMessage().contains("left unread"), e.getMessage());

        StateInput in3 = new StateInput(new ByteArrayInputStream(b));
        StateInput.Record r = in3.record("ONE ");
        e = assertThrows(StateFormatException.class, () -> r.readInt("answer", 0, 41));
        assertTrue(e.getMessage().contains("answer 42 outside 0..41"), e.getMessage());
    }

    @Test
    void readingPastARecordIsAFormatError() throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        StateOutput out = new StateOutput(bo);
        out.record("ONE ", d -> d.writeShort(1));
        out.finish();
        StateInput in = new StateInput(new ByteArrayInputStream(bo.toByteArray()));
        StateInput.Record r = in.record("ONE ");
        StateFormatException e = assertThrows(StateFormatException.class, r::readInt);
        assertTrue(e.getMessage().contains("ends early"), e.getMessage());
    }

    @Test
    void ramRoundTripsAndZeroPagesAreElided() throws IOException {
        // Not a multiple of the chunk or page size: the last page is partial.
        long size = 3 * (1L << 20) + 12345;
        PhysicalMemory a = new PhysicalMemory(size);
        Random rnd = new Random(7);
        byte[] page = new byte[4096];
        for (long p : new long[] {0, 5 << 12, (1L << 20) - 4096, 2L << 20, size - 1000}) {
            rnd.nextBytes(page);
            a.write(p, page, 0, (int) Math.min(page.length, size - p));
        }
        a.write64be(0x123456, 0x0102030405060708L);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        StateOutput out = new StateOutput(bo);
        a.saveState(out);
        out.finish();
        assertTrue(bo.size() < 8 * 4096 + 1024, "zero pages are not elided: " + bo.size() + " bytes");

        PhysicalMemory b = new PhysicalMemory(size);
        b.fill(0, size, (byte) 0x5a); // loading replaces everything, zero pages included
        StateInput in = new StateInput(new ByteArrayInputStream(bo.toByteArray()));
        b.loadState(in);
        in.finish();
        byte[] x = new byte[(int) size];
        byte[] y = new byte[(int) size];
        a.read(0, x, 0, x.length);
        b.read(0, y, 0, y.length);
        assertArrayEquals(x, y);

        PhysicalMemory c = new PhysicalMemory(size + 4096);
        StateInput in2 = new StateInput(new ByteArrayInputStream(bo.toByteArray()));
        StateMismatchException e = assertThrows(StateMismatchException.class, () -> c.loadState(in2));
        assertTrue(e.getMessage().contains("bytes of RAM"), e.getMessage());
    }

    @Test
    void loadingRamInvalidatesTranslatedCode() throws IOException {
        PhysicalMemory a = new PhysicalMemory(1L << 20);
        a.write32be(0x1000, 0x60000000);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        StateOutput out = new StateOutput(bo);
        a.saveState(out);
        out.finish();
        int gen = a.watchCode(0x1000);
        a.loadState(new StateInput(new ByteArrayInputStream(bo.toByteArray())));
        assertTrue(a.codeGeneration(0x1000) != gen, "translated code survived a load");
    }

    @Test
    void cpuStateRoundTrips() throws IOException {
        CpuState s = new CpuState();
        Random rnd = new Random(3);
        s.index = 2;
        for (int i = 0; i < 32; i++) {
            s.gpr[i] = rnd.nextLong();
        }
        for (int i = 0; i < 64; i++) {
            s.vsrHi[i] = rnd.nextLong();
            s.vsrLo[i] = rnd.nextLong();
        }
        s.pc = 0x1234;
        s.npc = 0x1238;
        s.lr = rnd.nextLong();
        s.ctr = rnd.nextLong();
        s.msr = Msr.SF | Msr.EE;
        s.cr = 0x12345678;
        s.setXer(0xe00c0055L);
        s.fpscr = 0xfff;
        s.vscr = 0x10001;
        s.spr[Spr.SPRG0] = 9;
        s.spr[1023] = -1;
        s.resValid = true;
        s.resAddr = 0x8000;
        s.resLen = 8;
        s.resVal = 77;
        s.procTable = 0x40000005L;
        s.ceded = true;
        s.halted = true;
        s.prodded = true;
        s.icount = 1L << 40;
        byte[] b = cpuBytes(s);

        CpuState t = new CpuState();
        t.index = 2;
        t.spr[100] = 5; // replaced
        StateInput in = state(b);
        t.loadState(in.record("CPU "));
        in.finish();
        assertArrayEquals(b, cpuBytes(t));
        assertEquals(0, t.spr[100]);
        assertEquals(0xe00c0055L, t.getXer());

        CpuState other = new CpuState();
        other.index = 1;
        StateFormatException e = assertThrows(StateFormatException.class,
                () -> other.loadState(state(b).record("CPU ")));
        assertTrue(e.getMessage().contains("CPU 2"), e.getMessage());

        s.runOnCpu(c -> { });
        assertThrows(IllegalStateException.class, () -> s.saveState(new DataOutputStream(new ByteArrayOutputStream())));
    }

    private static byte[] cpuBytes(CpuState s) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        StateOutput out = new StateOutput(bo);
        out.record("CPU ", s::saveState);
        out.finish();
        return bo.toByteArray();
    }

    private static StateInput state(byte[] b) throws IOException {
        return new StateInput(new ByteArrayInputStream(b));
    }
}
