package javapower.papr;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Random;
import javapower.core.CpuState;
import javapower.core.PhysicalMemory;

/** Test harness: a machine, its CPU, and helpers to make hypercalls and RTAS calls. */
final class Rig {
    final Machine m;
    final CpuState cpu = new CpuState();
    final PhysicalMemory mem;
    final BufferCharBackend console;

    /** Scratch area for RTAS argument blocks. */
    static final long RTAS_BUF = 0x10000;

    Rig(MachineConfig cfg) {
        try {
            if (cfg.console == null) {
                cfg.console(new BufferCharBackend());
            }
            if (cfg.firmwareImage == null) {
                cfg.firmware(new byte[] {0x48, 0x00, 0x00, 0x00}); // "b ." stand-in for SLOF
            }
            if (cfg.random == null) {
                cfg.random(new Random(1));
            }
            console = (BufferCharBackend) cfg.console;
            m = new Machine(cfg);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        mem = m.memory();
        m.attachCpu(cpu);
        m.reset(cpu);
    }

    Rig() {
        this(new MachineConfig().ram(256L << 20));
    }

    /** Make hypercall {@code op} with r4.. = args; returns r3. */
    long hcall(long op, long... args) {
        cpu.gpr[3] = op;
        for (int i = 0; i < 9; i++) {
            cpu.gpr[4 + i] = i < args.length ? args[i] : 0;
        }
        m.hcall(cpu);
        return cpu.gpr[3];
    }

    long r(int n) {
        return cpu.gpr[n];
    }

    /** Make an RTAS call; returns the nret return words (rets[0] is the status). */
    int[] rtas(String name, int nret, int... args) {
        return rtas(token(name), nret, args);
    }

    int[] rtas(int token, int nret, int... args) {
        long b = RTAS_BUF;
        mem.write32be(b, token);
        mem.write32be(b + 4, args.length);
        mem.write32be(b + 8, nret);
        for (int i = 0; i < args.length; i++) {
            mem.write32be(b + 12 + 4L * i, args[i]);
        }
        long rets = b + 12 + 4L * args.length;
        for (int i = 0; i < nret; i++) {
            mem.write32be(rets + 4L * i, 0xdeadbeef);
        }
        long st = hcall(H.KVMPPC_H_RTAS, b);
        if (st != H.SUCCESS) {
            throw new AssertionError("H_RTAS returned " + st);
        }
        int[] r = new int[nret];
        for (int i = 0; i < nret; i++) {
            r[i] = mem.read32be(rets + 4L * i);
        }
        return r;
    }

    static int token(String name) {
        for (int i = 0; i < DeviceTree.RTAS_TOKENS.length; i++) {
            if (DeviceTree.RTAS_TOKENS[i].equals(name)) {
                return Rtas.TOKEN_BASE + i;
            }
        }
        throw new IllegalArgumentException(name);
    }

    static byte[] resource(String name) {
        try (InputStream in = Rig.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalArgumentException("missing resource " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void writeBytes(long addr, byte... b) {
        mem.write(addr, b, 0, b.length);
    }

    byte[] readBytes(long addr, int len) {
        byte[] b = new byte[len];
        mem.read(addr, b, 0, len);
        return b;
    }
}
