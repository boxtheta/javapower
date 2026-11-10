package javapower.cpu;

import javapower.core.CpuState;
import javapower.core.DecodeTable;
import javapower.core.Msr;
import javapower.core.PhysicalMemory;

/** Rough interpreter throughput on an integer loop with loads/stores (not a test). */
public final class Bench {
    private Bench() {}

    public static void main(String[] args) {
        PhysicalMemory pm = new PhysicalMemory(1 << 20);
        CpuState s = new CpuState();
        DecodeTable t = new DecodeTable();
        t.addAll(new IntInsns());
        Interpreter cpu = new Interpreter(s, pm, t);
        cpu.resetPapr();
        s.msr = Msr.SF | Msr.ME | Msr.LE;
        int[] loop = {
            0x38630001, // addi r3,r3,1
            0x7c842a14, // add r4,r4,r5
            0x90640000 | (6 << 16), // stw r3,0(r6)
            0x80e60000, // lwz r7,0(r6)
            0x7c843a78, // xor r4,r4,r7
            0x5484083c, // rlwinm r4,r4,1,0,30
            0x2c030000, // cmpwi r3,0
            0x4bffffe4, // b loop
        };
        for (int k = 0; k < loop.length; k++) {
            pm.write32le(0x1000 + 4L * k, loop[k]);
        }
        s.gpr[6] = 0x8000;
        for (int round = 0; round < 5; round++) {
            s.pc = 0x1000;
            long t0 = System.nanoTime();
            long n = cpu.run(200_000_000L);
            double sec = (System.nanoTime() - t0) / 1e9;
            System.out.printf("%d insns in %.2f s: %.0f MIPS%n", n, sec, n / sec / 1e6);
        }
    }
}
