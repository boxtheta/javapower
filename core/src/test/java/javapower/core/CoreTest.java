package javapower.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CoreTest {
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
    }
}
