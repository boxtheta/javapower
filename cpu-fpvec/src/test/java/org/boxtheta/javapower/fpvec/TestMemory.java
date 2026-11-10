package org.boxtheta.javapower.fpvec;

import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.GuestMemory;
import org.boxtheta.javapower.core.PpcException;

/** A small flat memory window for tests; byte order follows the CPU's MSR[LE]. */
final class TestMemory implements GuestMemory {
    final long base;
    final byte[] bytes;
    private final CpuState cpu;

    TestMemory(CpuState cpu, long base, int size) {
        this.cpu = cpu;
        this.base = base;
        this.bytes = new byte[size];
    }

    private int idx(long ea, int n) {
        long off = ea - base;
        if (off < 0 || off + n > bytes.length) {
            throw PpcException.dsi(ea, PpcException.DSISR_NOPTE);
        }
        return (int) off;
    }

    private long readN(long ea, int n) {
        int o = idx(ea, n);
        long v = 0;
        if (cpu.littleEndian()) {
            for (int i = n - 1; i >= 0; i--) v = (v << 8) | (bytes[o + i] & 0xff);
        } else {
            for (int i = 0; i < n; i++) v = (v << 8) | (bytes[o + i] & 0xff);
        }
        return v;
    }

    private void writeN(long ea, int n, long v) {
        int o = idx(ea, n);
        if (cpu.littleEndian()) {
            for (int i = 0; i < n; i++) bytes[o + i] = (byte) (v >>> (8 * i));
        } else {
            for (int i = 0; i < n; i++) bytes[o + i] = (byte) (v >>> (8 * (n - 1 - i)));
        }
    }

    @Override public int read8(long ea) { return (int) readN(ea, 1); }
    @Override public int read16(long ea) { return (int) readN(ea, 2); }
    @Override public int read32(long ea) { return (int) readN(ea, 4); }
    @Override public long read64(long ea) { return readN(ea, 8); }
    @Override public void write8(long ea, int v) { writeN(ea, 1, v); }
    @Override public void write16(long ea, int v) { writeN(ea, 2, v); }
    @Override public void write32(long ea, int v) { writeN(ea, 4, v); }
    @Override public void write64(long ea, long v) { writeN(ea, 8, v); }

    @Override
    public long translateData(long ea, boolean store) {
        idx(ea, 1);
        return ea;
    }
}
