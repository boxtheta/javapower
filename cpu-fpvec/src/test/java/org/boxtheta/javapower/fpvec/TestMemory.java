package org.boxtheta.javapower.fpvec;

import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.GuestMemory;
import org.boxtheta.javapower.core.PhysicalMemory;
import org.boxtheta.javapower.core.PpcException;

/**
 * A small flat memory window for tests; byte order follows the CPU's MSR[LE]. Without a fast
 * path the window is {@link #bytes}; with one ({@link #fastPath}) it is guest RAM that
 * {@link #ramAddress} hands out for every access within the window and a 4 KiB page, as an MMU
 * does on a TLB hit, and {@link #contents} reads it back.
 */
final class TestMemory implements GuestMemory {
    final long base;
    final byte[] bytes;
    private final CpuState cpu;
    private PhysicalMemory ram;

    TestMemory(CpuState cpu, long base, int size) {
        this.cpu = cpu;
        this.base = base;
        this.bytes = new byte[size];
    }

    /** From now on the window is guest RAM, initialised from {@link #bytes}, with a fast path. */
    TestMemory fastPath() {
        ram = new PhysicalMemory(bytes.length);
        ram.write(0, bytes, 0, bytes.length);
        return this;
    }

    /** The window's contents now. */
    byte[] contents() {
        if (ram == null) {
            return bytes.clone();
        }
        byte[] b = new byte[bytes.length];
        ram.read(0, b, 0, b.length);
        return b;
    }

    private int idx(long ea, int n) {
        long off = ea - base;
        if (off < 0 || off + n > bytes.length) {
            throw PpcException.dsi(ea, PpcException.DSISR_NOPTE);
        }
        return (int) off;
    }

    private int get(int o) {
        return ram == null ? bytes[o] & 0xff : ram.read8(o);
    }

    private void put(int o, int b) {
        if (ram == null) {
            bytes[o] = (byte) b;
        } else {
            ram.write8(o, b);
        }
    }

    private long readN(long ea, int n) {
        int o = idx(ea, n);
        long v = 0;
        if (cpu.littleEndian()) {
            for (int i = n - 1; i >= 0; i--) v = (v << 8) | get(o + i);
        } else {
            for (int i = 0; i < n; i++) v = (v << 8) | get(o + i);
        }
        return v;
    }

    private void writeN(long ea, int n, long v) {
        int o = idx(ea, n);
        if (cpu.littleEndian()) {
            for (int i = 0; i < n; i++) put(o + i, (int) (v >>> (8 * i)) & 0xff);
        } else {
            for (int i = 0; i < n; i++) put(o + i, (int) (v >>> (8 * (n - 1 - i))) & 0xff);
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

    @Override
    public long ramAddress(long ea, int len, boolean store) {
        long off = ea - base;
        if (ram == null || off < 0 || off + len > bytes.length || (ea & 0xfff) + len > 0x1000) {
            return -1;
        }
        return off;
    }

    @Override
    public PhysicalMemory ram() {
        return ram;
    }
}
