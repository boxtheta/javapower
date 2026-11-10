package org.boxtheta.javapower.papr;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Random;
import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.PhysicalMemory;

/**
 * Test harness: a machine, its CPUs, and helpers to make hypercalls and RTAS calls. Nothing runs
 * the CPUs; a test "executes" on CPU n by calling the helpers with {@code cpus[n]}.
 */
final class Rig {
    final Machine m;
    /** All CPUs; {@code cpus[i].index == i}. */
    final CpuState[] cpus;
    /** The boot CPU, {@code cpus[0]}. */
    final CpuState cpu;
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
        cpus = new CpuState[m.cpuCount()];
        for (int i = 0; i < cpus.length; i++) {
            cpus[i] = new CpuState();
        }
        cpu = cpus[0];
        if (cpus.length == 1) {
            m.attachCpu(cpu);
            m.reset(cpu);
        } else {
            m.attachCpus(java.util.List.of(cpus));
            m.reset();
        }
    }

    /** A 256 MiB machine with {@code n} CPUs. */
    static Rig smp(int n) {
        return new Rig(new MachineConfig().ram(256L << 20).cpus(n));
    }

    Rig() {
        this(new MachineConfig().ram(256L << 20));
    }

    /** Make hypercall {@code op} on the boot CPU with r4.. = args; returns r3. */
    long hcall(long op, long... args) {
        return hcallOn(cpu, op, args);
    }

    /** Make hypercall {@code op} on CPU {@code c} with r4.. = args; returns r3. */
    long hcallOn(CpuState c, long op, long... args) {
        c.gpr[3] = op;
        for (int i = 0; i < 9; i++) {
            c.gpr[4 + i] = i < args.length ? args[i] : 0;
        }
        m.hcall(c);
        return c.gpr[3];
    }

    long r(int n) {
        return cpu.gpr[n];
    }

    /** Make an RTAS call; returns the nret return words (rets[0] is the status). */
    int[] rtas(String name, int nret, int... args) {
        return rtas(token(name), nret, args);
    }

    int[] rtas(int token, int nret, int... args) {
        return rtasOn(cpu, RTAS_BUF, token, nret, args);
    }

    /** RTAS call made by CPU {@code c} with its argument block at {@code b}. */
    int[] rtasOn(CpuState c, long b, String name, int nret, int... args) {
        return rtasOn(c, b, token(name), nret, args);
    }

    int[] rtasOn(CpuState c, long b, int token, int nret, int... args) {
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
        long st = hcallOn(c, H.KVMPPC_H_RTAS, b);
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
        for (int i = 0; i < DeviceTree.RTAS_TOKENS.size(); i++) {
            if (DeviceTree.RTAS_TOKENS.get(i).equals(name)) {
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
