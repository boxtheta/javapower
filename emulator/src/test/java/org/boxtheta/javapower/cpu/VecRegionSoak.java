package org.boxtheta.javapower.cpu;

import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import org.boxtheta.javapower.core.Msr;

/**
 * The {@link VecRegionFuzzTest} check at length, run by hand: {@code VecRegionSoak <first seed>
 * <cases> [threads]}. Each case draws its mode (byte order, computation mode, translation,
 * problem state, quadrant, decrementer, facilities) from its seed. Prints failures and totals.
 */
public final class VecRegionSoak {
    private VecRegionSoak() {}

    public static void main(String[] args) throws InterruptedException {
        int first = Integer.parseInt(args[0]);
        int n = Integer.parseInt(args[1]);
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        AtomicLong next = new AtomicLong(first), failures = new AtomicLong(), insns = new AtomicLong();
        long t0 = System.nanoTime();
        Thread[] th = new Thread[threads];
        for (int k = 0; k < threads; k++) {
            th[k] = new Thread(() -> {
                long seed;
                while ((seed = next.getAndIncrement()) < first + n) {
                    Random r = new Random(seed * 1_000_003L);
                    boolean dr = r.nextInt(3) != 0;
                    boolean pr = dr && r.nextInt(3) == 0;
                    boolean sf = r.nextInt(4) != 0;
                    long fac = Msr.FP | Msr.VEC | Msr.VSX;
                    if (r.nextInt(12) == 0) {
                        fac &= r.nextBoolean() ? ~Msr.VEC : ~Msr.VSX;
                    }
                    VecRegionFuzzTest.Mode mode = new VecRegionFuzzTest.Mode(r.nextBoolean(), sf, dr, pr,
                            dr && sf && r.nextInt(3) == 0, r.nextInt(3) == 0, fac);
                    try {
                        insns.addAndGet(VecRegionFuzzTest.run((int) seed, mode, 30_000)[1]);
                    } catch (AssertionError e) {
                        failures.incrementAndGet();
                        System.out.println("FAIL seed " + seed + ": " + e.getMessage());
                    }
                }
            });
            th[k].start();
        }
        for (Thread t : th) t.join();
        System.out.printf("TOTAL %d cases from seed %d, %d failures, %d interpreted instructions, %.1f s%n", n, first,
                failures.get(), insns.get(), (System.nanoTime() - t0) / 1e9);
    }
}
