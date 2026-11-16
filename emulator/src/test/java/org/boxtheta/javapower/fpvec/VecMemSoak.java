package org.boxtheta.javapower.fpvec;

/**
 * The {@link VecMemDiffTest} check at length, run by hand: {@code VecMemSoak <seed> <cases per
 * form> [threads]}. Prints the cases, faults and mismatches per form and in total.
 */
public final class VecMemSoak {
    private VecMemSoak() {}

    public static void main(String[] args) throws InterruptedException {
        long seed = Long.decode(args[0]);
        int n = Integer.parseInt(args[1]);
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        VecMemDiff[] d = new VecMemDiff[threads];
        Thread[] th = new Thread[threads];
        long t0 = System.nanoTime();
        for (int k = 0; k < threads; k++) {
            VecMemDiff v = d[k] = new VecMemDiff(seed + k);
            th[k] = new Thread(() -> v.runAll(n));
            th[k].start();
        }
        for (Thread t : th) t.join();
        long cases = 0, bad = 0, faults = 0;
        for (String name : d[0].counts.keySet()) {
            long c = 0, b = 0, f = 0;
            for (VecMemDiff v : d) {
                c += v.counts.get(name)[0];
                b += v.counts.get(name)[1];
                f += v.counts.get(name)[2];
            }
            System.out.printf("%-10s %12d cases %10d faults %8d mismatches%n", name, c, f, b);
            cases += c;
            bad += b;
            faults += f;
        }
        for (VecMemDiff v : d) {
            bad += v.mismatches - v.counts.values().stream().mapToLong(c -> c[1]).sum(); // full checks
            System.out.print(v.report);
        }
        System.out.printf("%nTOTAL %d cases, %d faults, %d mismatches, %d forms, seed %#x, %.1f s%n", cases, faults,
                bad, d[0].counts.size(), seed, (System.nanoTime() - t0) / 1e9);
    }
}
