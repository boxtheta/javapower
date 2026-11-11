package org.boxtheta.javapower.papr;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.Random;

/**
 * A virtio entropy device (virtio-rng, device type 4), QEMU's {@code virtio-rng-pci}: the guest
 * posts buffers on its one queue and the device fills them with random bytes. Linux uses it as
 * a {@code hw_random} source ({@code /dev/hwrng}, driver {@code virtio_rng}), FreeBSD as an
 * entropy source ({@code vtrnd}, module {@code virtio_random}).
 *
 * <pre>{@code
 * cfg.addPciDevice(new VirtioPciDevice(new VirtioRng()));
 * }</pre>
 *
 * <p>Requests are filled at once, on the CPU thread that kicks the queue, without the rate
 * limiting QEMU's backend can apply.
 */
public final class VirtioRng extends VirtioDevice {
    /** The virtio device type. */
    public static final int TYPE = 4;
    /** Size of the request queue, as in QEMU. */
    public static final int QUEUE_SIZE = 8;

    private final Random random;
    private final java.util.concurrent.atomic.AtomicLong served = new java.util.concurrent.atomic.AtomicLong();

    /** An entropy device fed from a {@link SecureRandom}. */
    public VirtioRng() {
        this(new SecureRandom());
    }

    /**
     * An entropy device fed from {@code random} (which must be thread-safe, as {@link Random} and
     * {@link SecureRandom} are), e.g. a seeded {@link Random} for reproducible runs.
     */
    public VirtioRng(Random random) {
        super(TYPE, QUEUE_SIZE);
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Random bytes handed to the guest since the device was created. Any thread. */
    public long bytesServed() {
        return served.get();
    }

    @Override
    protected void queueNotified(Virtqueue q) {
        drain(q);
    }

    @Override
    protected void driverReady() {
        // Requests made available before DRIVER_OK, or redelivered after a load.
        drain(queue(0));
    }

    private void drain(Virtqueue q) {
        VirtqueueRequest r;
        while ((r = q.pop()) != null) {
            int n = (int) Math.min(r.writableLength(), 1 << 16);
            byte[] b = new byte[n];
            random.nextBytes(b);
            int w = r.write(0, b, 0, n);
            if (r.complete(Math.max(w, 0))) {
                served.addAndGet(w);
            }
        }
    }
}
