package org.boxtheta.javapower.fpvec;

/**
 * The {@link VmxDiffTest} check at length, run by hand: {@code VmxSoak <seed> <cases per
 * instruction> [threads]}. Prints the cases and mismatches per instruction and in total.
 */
public final class VmxSoak {
    private VmxSoak() {}

    public static void main(String[] args) throws InterruptedException {
        long seed = Long.decode(args[0]);
        int n = Integer.parseInt(args[1]);
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        VmxDiff[] d = new VmxDiff[threads];
        Thread[] th = new Thread[threads];
        long t0 = System.nanoTime();
        for (int k = 0; k < threads; k++) {
            VmxDiff v = d[k] = new VmxDiff(seed + k);
            th[k] = new Thread(() -> v.runAll(n));
            th[k].start();
        }
        for (Thread t : th) t.join();
        long cases = 0, bad = 0;
        for (String name : d[0].counts.keySet()) {
            long c = 0, b = 0;
            for (VmxDiff v : d) {
                c += v.counts.get(name)[0];
                b += v.counts.get(name)[1];
            }
            System.out.printf("%-14s %12d cases %8d mismatches%n", name, c, b);
            cases += c;
            bad += b;
        }
        for (VmxDiff v : d) System.out.print(v.report);
        System.out.printf("%nTOTAL %d cases, %d mismatches, %d instructions, seed %#x, %.1f s%n", cases, bad,
                d[0].counts.size(), seed, (System.nanoTime() - t0) / 1e9);
    }
}
