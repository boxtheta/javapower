package org.boxtheta.javapower.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CoreTest {
    @Test
    void postedWorkRunsInOrderOnDemand() throws Exception {
        CpuState s = new CpuState();
        java.util.List<Integer> seen = new java.util.ArrayList<>();
        assertEquals(false, s.workPending);
        s.runOnCpu(c -> seen.add(1));
        Thread t = new Thread(() -> s.runOnCpu(c -> seen.add(2)));
        t.start();
        t.join();
        assertEquals(true, s.workPending);
        assertEquals(java.util.List.of(), seen);
        s.runPostedWork();
        assertEquals(java.util.List.of(1, 2), seen);
        assertEquals(false, s.workPending);
        s.runPostedWork();
        assertEquals(java.util.List.of(1, 2), seen);
    }

    @Test
    void xerRoundTrips() {
        CpuState s = new CpuState();
        s.setXer(0xffffffffffffffffL);
        assertEquals(0xe00c007fL, s.getXer());
        s.setXer(0x20000000L);
        assertEquals(1, s.ca);
        assertEquals(0, s.so);
    }

    @Test
    void crFields() {
        CpuState s = new CpuState();
        s.setCrField(0, 0x8);
        s.setCrField(7, 0x1);
        assertEquals(0x80000001, s.cr);
        assertEquals(8, s.getCrField(0));
        s.msr = 0; // 32-bit mode: only the low word decides the sign
        s.setCr0(0x80000000L);
        assertEquals(8, s.getCrField(0));
        s.msr = Msr.SF;
        s.setCr0(0x80000000L);
        assertEquals(4, s.getCrField(0));
    }

    @Test
    void decodePrefersSpecificPattern() {
        DecodeTable t = new DecodeTable();
        Op generic = (s, i) -> {};
        Op specific = (s, i) -> {};
        t.addX(31, 444, "or", generic);
        t.add(0xffffffff, 0x7c210b78, "or r1,r1,r1", specific);
        assertEquals("or r1,r1,r1", t.lookup(0x7c210b78).name());
        assertEquals("or", t.lookup(0x7c631b78).name());
        assertNull(t.lookup(0x7c000000 | (999 << 1)));
        assertThrows(IllegalArgumentException.class, () -> t.addX(31, 444, "dup", generic));
    }

    @Test
    void physicalMemoryByteOrder() {
        PhysicalMemory m = new PhysicalMemory(1 << 16);
        m.write64be(0x101, 0x0102030405060708L);
        assertEquals(0x01, m.read8(0x101));
        assertEquals(0x0807060504030201L, m.read64le(0x101));
        assertEquals(0x0403, m.read16le(0x103));
        assertEquals(true, m.contains(0xfff8, 8));
        assertEquals(false, m.contains(0xfff9, 8));
        assertThrows(IndexOutOfBoundsException.class, () -> m.read32le(0xfffe));
        assertThrows(IndexOutOfBoundsException.class, () -> m.write16be(0x10000, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> m.read8(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> m.read64be(1L << 62));
    }

    /** PhysicalMemory is multi-release: the version in use is the one for the running Java. */
    @Test
    void physicalMemoryVersionMatchesTheRunningJava() {
        Object backing = new PhysicalMemory(1 << 16).jitBacking();
        boolean segments = Runtime.version().feature() >= 22;
        assertEquals(segments, backing.getClass().getName().contains("MemorySegment"), backing.getClass().getName());
        assertEquals(!segments, backing instanceof byte[][], backing.getClass().getName());
    }

    /** On Java 21 RAM is held in 1 GiB chunks: accesses and copies across a chunk boundary behave as anywhere else. */
    @Test
    void physicalMemoryAcrossChunks() {
        long b = 1L << 30;
        PhysicalMemory m = new PhysicalMemory(b + (1 << 16));
        for (int k = 1; k < 8; k++) {
            long a = b - k;
            m.write64be(a, 0x0102030405060708L);
            assertEquals(0x0102030405060708L, m.read64be(a));
            assertEquals(0x0807060504030201L, m.read64le(a));
            assertEquals(0x01020304, m.read32be(a));
            assertEquals(0x04030201, m.read32le(a));
            assertEquals(0x0201, m.read16le(a));
            m.write32le(a, 0x11223344);
            assertEquals(0x44332211, m.read32be(a));
            m.write16be(a, 0xa1b2);
            assertEquals(0xb2a1, m.read16le(a));
            assertEquals(0xa1b2, m.read16be(a));
        }
        byte[] src = new byte[64];
        for (int k = 0; k < src.length; k++) {
            src[k] = (byte) (k + 1);
        }
        m.write(b - 32, src, 0, 64);
        byte[] dst = new byte[64];
        m.read(b - 32, dst, 0, 64);
        assertEquals(java.util.Arrays.toString(src), java.util.Arrays.toString(dst));
        assertEquals(0x2021, m.read16be(b - 1));
        m.fill(b - 3, 6, (byte) 0x5a);
        assertEquals(0x5a5a5a5a5a5a1d1cL, m.read64le(b - 5));
        assertEquals(true, m.cas64be(b, 0x5a5a5a2425262728L, 7));
        assertEquals(7, m.read64be(b));
        assertEquals(true, m.cas16le(b - 2, 0x5a5a, 0x1234));
        assertEquals(0x1234, m.read16le(b - 2));
        assertThrows(IndexOutOfBoundsException.class, () -> m.read64le(b + (1 << 16) - 4));
        assertThrows(IndexOutOfBoundsException.class, () -> m.write32be(b + (1 << 16) - 2, 0));
    }
}
