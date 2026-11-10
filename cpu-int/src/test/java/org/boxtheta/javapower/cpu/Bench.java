package org.boxtheta.javapower.cpu;

import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.DecodeTable;
import org.boxtheta.javapower.core.Msr;
import org.boxtheta.javapower.core.PhysicalMemory;

/**
 * Rough interpreter throughput on a little-endian real-mode loop (not a test). Arguments:
 * {@code [rounds] [mix|nop|ld]}: an integer loop with loads/stores (default), a loop of nops
 * (run-loop overhead), or a doubleword load/store loop. Pass {@code -Dnojit=true} to measure the
 * interpreter alone.
 */
public final class Bench {
    private Bench() {}

    public static void main(String[] args) {
        PhysicalMemory pm = new PhysicalMemory(1 << 20);
        CpuState s = new CpuState();
        DecodeTable t = new DecodeTable();
        t.addAll(new IntInsns());
        Interpreter cpu = new Interpreter(s, pm, t);
        cpu.compileBlocks = !Boolean.getBoolean("nojit");
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
        if (args.length > 1 && args[1].equals("nop")) {
            loop = new int[] {0x60000000, 0x60000000, 0x60000000, 0x60000000, 0x60000000, 0x60000000, 0x60000000,
                0x4bffffe4};
        } else if (args.length > 1 && args[1].equals("ld")) {
            // ld r7,0(r6); std r7,8(r6); addi r3,r3,1; ld r8,8(r6); add r4,r4,r8; std r4,16(r6); cmpi; b
            loop = new int[] {0xe8e60000, 0xf8e60008, 0x38630001, 0xe9060008, 0x7c844214, 0xf8860010, 0x2c030000,
                0x4bffffe4};
        }
        for (int k = 0; k < loop.length; k++) {
            pm.write32le(0x1000 + 4L * k, loop[k]);
        }
        s.gpr[6] = 0x8000;
        int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 5;
        for (int round = 0; round < rounds; round++) {
            s.pc = 0x1000;
            long t0 = System.nanoTime();
            long n = cpu.run(200_000_000L);
            double sec = (System.nanoTime() - t0) / 1e9;
            System.out.printf("%d insns in %.2f s: %.0f MIPS%n", n, sec, n / sec / 1e6);
        }
    }
}
