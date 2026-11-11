package org.boxtheta.javapower.cpu;

/**
 * The source of {@code darn}: the SplitMix64 generator, producing exactly the sequence of
 * {@link java.util.SplittableRandom#nextLong()} for the same seed, but with a state that can be
 * read and set, so a saved machine continues the same sequence. Owned by one CPU's thread.
 */
final class DarnRandom {
    private static final long GAMMA = 0x9e3779b97f4a7c15L;

    /** The generator state; the next value is mix(state + GAMMA). */
    long state;

    DarnRandom(long seed) {
        this.state = seed;
    }

    long nextLong() {
        long z = (state += GAMMA);
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
