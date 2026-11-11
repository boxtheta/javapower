package org.boxtheta.javapower.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MmioBusTest {
    /** Returns {@code 0x1122334455667788} and records writes. */
    static final class Fixed implements MmioHandler {
        final List<String> writes = new ArrayList<>();

        @Override
        public long read(long offset, int size) {
            return 0x1122334455667788L;
        }

        @Override
        public void write(long offset, int size, long value) {
            writes.add(Long.toHexString(offset) + ":" + size + ":" + Long.toHexString(value));
        }
    }

    static MmioRegion region(String name, long base, long size) {
        return new MmioRegion(name, base, size, ByteOrder.BIG_ENDIAN, new Fixed());
    }

    @Test
    void lookups() {
        MmioBus bus = new MmioBus();
        assertTrue(bus.isEmpty());
        MmioRegion a = region("a", 0x1000, 0x100);
        MmioRegion b = region("b", 0x3000, 6);
        MmioRegion c = region("c", 0x1100, 0x100);
        bus.map(b);
        bus.map(a);
        bus.map(c);
        assertEquals(List.of(a, c, b), bus.regions());
        assertNull(bus.find(0xfff));
        assertSame(a, bus.find(0x1000));
        assertSame(a, bus.find(0x10ff));
        assertSame(c, bus.find(0x1100));
        assertNull(bus.find(0x1200));
        assertSame(b, bus.find(0x3005));
        assertNull(bus.find(0x3006));
        assertNull(bus.find(-1));
        // regionFor: naturally aligned and wholly inside one region.
        assertSame(a, bus.regionFor(0x10f8, 8));
        assertNull(bus.regionFor(0x10fc, 8), "unaligned");
        assertNull(bus.regionFor(0x10fe, 4), "unaligned, crossing into c");
        assertSame(b, bus.regionFor(0x3004, 2));
        assertNull(bus.regionFor(0x3004, 4), "leaves b");
        assertNull(bus.regionFor(Long.MIN_VALUE, 1));
    }

    @Test
    void mapAndUnmap() {
        MmioBus bus = new MmioBus();
        MmioRegion a = region("a", 0x1000, 0x100);
        bus.map(a);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> bus.map(region("x", 0x10ff, 2)));
        assertTrue(e.getMessage().contains("overlaps a"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> bus.map(a));
        bus.map(region("below", 0xf00, 0x100));
        assertTrue(bus.unmap(a));
        assertFalse(bus.unmap(a));
        assertNull(bus.find(0x1000));
        bus.map(region("again", 0x1000, 0x100));
        assertEquals(2, bus.regions().size());
    }

    @Test
    void invalidRegions() {
        assertThrows(IllegalArgumentException.class, () -> region("empty", 0x1000, 0));
        assertThrows(IllegalArgumentException.class, () -> region("negative", -0x1000, 0x10));
        assertThrows(IllegalArgumentException.class, () -> region("overflow", Long.MAX_VALUE - 4, 0x10));
    }

    @Test
    void byteOrderConversion() {
        Fixed h = new Fixed();
        MmioRegion be = new MmioRegion("be", 0, 0x10, ByteOrder.BIG_ENDIAN, h);
        MmioRegion le = new MmioRegion("le", 0, 0x10, ByteOrder.LITTLE_ENDIAN, h);
        // Same order: the handler's value, cut to the access size.
        assertEquals(0x88L, be.read(0, 1, ByteOrder.BIG_ENDIAN));
        assertEquals(0x7788L, be.read(0, 2, ByteOrder.BIG_ENDIAN));
        assertEquals(0x55667788L, le.read(0, 4, ByteOrder.LITTLE_ENDIAN));
        // Other order: byte-reversed within the access size.
        assertEquals(0x8877L, be.read(0, 2, ByteOrder.LITTLE_ENDIAN));
        assertEquals(0x88776655L, le.read(0, 4, ByteOrder.BIG_ENDIAN));
        assertEquals(0x8877665544332211L, le.read(0, 8, ByteOrder.BIG_ENDIAN));
        be.write(4, 4, 0xffffffff11223344L, ByteOrder.BIG_ENDIAN);
        be.write(4, 4, 0x11223344L, ByteOrder.LITTLE_ENDIAN);
        le.write(2, 2, 0xabcd, ByteOrder.BIG_ENDIAN);
        le.write(1, 1, 0x1ff, ByteOrder.BIG_ENDIAN);
        assertEquals(List.of("4:4:11223344", "4:4:44332211", "2:2:cdab", "1:1:ff"), h.writes);
    }
}
