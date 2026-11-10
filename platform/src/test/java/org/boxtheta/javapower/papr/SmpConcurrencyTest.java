package org.boxtheta.javapower.papr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.Spr;
import org.junit.jupiter.api.Test;

/**
 * Hypercalls from several host threads at once, each thread playing one CPU: IPIs and EOIs
 * between every pair of CPUs, console output and input, SCSI commands, and CPUs being started and
 * stopped while the others keep going. Lost interrupts or wake-ups show up as timeouts, torn
 * state as wrong values.
 */
class SmpConcurrencyTest {
    static final int VTY = 0x71000001;
    static final int VSCSI = 0x71000002;
    static final long START = 0x4000;
    static final long TIMEOUT_NS = 60_000_000_000L;

    final ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

    Thread spawn(String name, Runnable body) {
        Thread t = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable e) {
                errors.add(e);
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    void joinAll(List<Thread> threads) throws InterruptedException {
        long end = System.nanoTime() + TIMEOUT_NS;
        for (Thread t : threads) {
            t.join(Math.max(1, (end - System.nanoTime()) / 1_000_000));
        }
        for (Throwable e : errors) {
            throw new AssertionError("worker failed", e);
        }
        for (Thread t : threads) {
            assertFalse(t.isAlive(), t.getName() + " did not finish (lost interrupt or wake-up?)");
        }
    }

    static void check(boolean ok, String msg) {
        if (!ok) {
            throw new AssertionError(msg);
        }
    }

    static long hc(Rig r, CpuState c, long op, long... args) {
        return r.hcallOn(c, op, args);
    }

    // ---- IPI protocol as Linux uses it (mailbox, then H_IPI; receiver clears MFRR, drains, EOIs)

    static final class Ipis {
        final AtomicInteger[] mailbox;
        final AtomicLong sent = new AtomicLong();
        final AtomicLong received = new AtomicLong();

        Ipis(int n) {
            mailbox = new AtomicInteger[n];
            for (int i = 0; i < n; i++) {
                mailbox[i] = new AtomicInteger();
            }
        }

        void send(Rig r, CpuState from, int to) {
            mailbox[to].incrementAndGet();
            sent.incrementAndGet();
            check(hc(r, from, H.IPI, to, 4) == H.SUCCESS, "H_IPI");
        }

        /** Take every interrupt pending on {@code c}. */
        void service(Rig r, CpuState c) {
            while (c.extIrq) {
                check(hc(r, c, H.XIRR) == H.SUCCESS, "H_XIRR");
                long xirr = c.gpr[4];
                int xisr = (int) (xirr & 0xffffff);
                if (xisr == Xics.IPI) {
                    check(hc(r, c, H.IPI, c.index, 0xff) == H.SUCCESS, "clear MFRR");
                    received.addAndGet(mailbox[c.index].getAndSet(0));
                } else {
                    check(xisr == 0, "unexpected source " + Integer.toHexString(xisr));
                }
                check(hc(r, c, H.EOI, xirr) == H.SUCCESS, "H_EOI");
            }
        }
    }

    // ---- a minimal v-scsi initiator (see VScsiTest for the protocol) ---------------------------

    static final long RA_BASE = 0x1000000;
    static final long LUN0 = VScsiTest.LUN0;

    static final class Scsi {
        final Rig r;
        final CpuState c;
        final byte[] disk;
        int crqNext;
        long tag;

        Scsi(Rig r, CpuState c, byte[] disk) {
            this.r = r;
            this.c = c;
            this.disk = disk;
        }

        byte[] nextCrq() {
            long a = RA_BASE + crqNext;
            byte[] e = r.readBytes(a, 16);
            check((e[0] & 0x80) != 0, "no CRQ response");
            r.mem.write8(a, 0);
            crqNext = (crqNext + 16) % 4096;
            return e;
        }

        byte[] send(byte[] iu) {
            r.writeBytes(RA_BASE + 0x1000, iu);
            long hi = (0x80L << 56) | ((long) VScsi.FORMAT_SRP << 48) | iu.length;
            check(hc(r, c, H.SEND_CRQ, VSCSI, hi, 0x1000) == H.SUCCESS, "H_SEND_CRQ");
            byte[] e = nextCrq();
            int len = ((e[6] & 0xff) << 8) | (e[7] & 0xff);
            return r.readBytes(RA_BASE + 0x1000, len);
        }

        void bringUp() {
            for (int i = 0; i < 64; i++) {
                check(hc(r, c, H.PUT_TCE, VSCSI, i * 4096L, (RA_BASE + i * 4096L) | 3) == H.SUCCESS, "H_PUT_TCE");
            }
            check(hc(r, c, H.REG_CRQ, VSCSI, 0, 4096) == H.SUCCESS, "H_REG_CRQ");
            check(hc(r, c, H.SEND_CRQ, VSCSI, 0xc001000000000000L, 0) == H.SUCCESS, "CRQ init");
            check(nextCrq()[1] == 2, "CRQ init response");
            check(send(VScsiTest.login(1))[0] == (byte) 0xc0, "SRP login");
            send(VScsiTest.cmd(++tag, LUN0, VScsiTest.cdb(0x00), false, 0, 0)); // eat the unit attention
        }

        void readAndVerify(int lba) {
            byte[] rsp = send(VScsiTest.cmd(++tag, LUN0, VScsiTest.cdb(0x28, 0, 0, 0, 0, lba, 0, 0, 1, 0), false,
                    0x10000, 4096));
            check(rsp[0] == (byte) 0xc1 && rsp[19] == 0, "READ(10) status");
            check(Arrays.equals(Arrays.copyOfRange(disk, lba * 4096, lba * 4096 + 4096),
                    r.readBytes(RA_BASE + 0x10000, 4096)), "READ(10) data");
        }
    }

    static Rig machine(int cpus, MemoryBackend disk) {
        MachineConfig c = new MachineConfig().ram(256L << 20).cpus(cpus);
        c.addDisk(ScsiDisk.Type.DISK, disk).blockSize(4096);
        return new Rig(c);
    }

    static MemoryBackend pattern() {
        MemoryBackend d = new MemoryBackend(4096 * 64);
        for (int i = 0; i < d.data().length; i++) {
            d.data()[i] = (byte) (i * 13 + (i >>> 12));
        }
        return d;
    }

    /** Start every secondary from CPU 0, as the guest would before the storm. */
    static void startAll(Rig r) {
        for (int i = 1; i < r.cpus.length; i++) {
            assertArrayEquals(new int[] {0}, r.rtasOn(r.cpu, Rig.RTAS_BUF, "start-cpu", 1, i, (int) START, i));
            r.cpus[i].runPostedWork();
        }
    }

    /** One console record: "C<cpu><6-digit sequence>", 8 bytes, written by one H_PUT_TERM_CHAR. */
    static long record(int cpu, int seq) {
        byte[] b = String.format("C%d%06d", cpu, seq).getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.wrap(b).getLong();
    }

    static void checkConsole(String out, int cpus, int[] perCpu) {
        assertEquals(0, out.length() % 8, "console output torn");
        int[] next = new int[cpus];
        for (int i = 0; i < out.length(); i += 8) {
            String rec = out.substring(i, i + 8);
            assertEquals('C', rec.charAt(0), "torn record " + rec);
            int cpu = rec.charAt(1) - '0';
            assertEquals(next[cpu]++, Integer.parseInt(rec.substring(2)), "CPU " + cpu + " output out of order");
        }
        assertArrayEquals(perCpu, next);
    }

    @Test
    void ipiConsoleAndScsiStorm() throws Exception {
        MemoryBackend disk = pattern();
        Rig r = machine(4, disk);
        startAll(r);
        int n = r.cpus.length;
        Ipis ipis = new Ipis(n);
        final int rounds = 20_000;
        final String typed;
        {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 20_000; i++) {
                sb.append((char) ('a' + i % 26));
            }
            typed = sb.toString();
        }
        StringBuffer got = new StringBuffer();
        int[] records = new int[n];
        AtomicInteger finished = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            CpuState c = r.cpus[i];
            int me = i;
            threads.add(spawn("cpu" + i, () -> {
                Random rnd = new Random(me);
                check(hc(r, c, H.CPPR, 0xff) == H.SUCCESS, "H_CPPR");
                Scsi scsi = me == 2 ? new Scsi(r, c, disk.data()) : null;
                if (scsi != null) {
                    scsi.bringUp();
                }
                int seq = 0;
                for (int k = 0; k < rounds; k++) {
                    int to = (me + 1 + rnd.nextInt(n - 1)) % n;
                    ipis.send(r, c, to);
                    ipis.service(r, c);
                    if (k % 16 == 0) {
                        check(hc(r, c, H.PUT_TERM_CHAR, VTY, 8, record(me, seq++), 0) == H.SUCCESS, "put");
                    }
                    if (scsi != null && k % 8 == 0) {
                        scsi.readAndVerify(rnd.nextInt(63));
                    }
                    if (me == 3 && k % 4 == 0) {
                        check(hc(r, c, H.GET_TERM_CHAR, VTY) == H.SUCCESS, "get");
                        int len = (int) c.gpr[4];
                        byte[] b = ByteBuffer.allocate(16).putLong(c.gpr[5]).putLong(c.gpr[6]).array();
                        got.append(new String(b, 0, len, StandardCharsets.ISO_8859_1));
                    }
                }
                records[me] = seq;
                finished.incrementAndGet();
                long end = System.nanoTime() + TIMEOUT_NS;
                // Keep taking interrupts until every IPI anyone sent has been received.
                while (finished.get() < n || ipis.received.get() < ipis.sent.get()) {
                    ipis.service(r, c);
                    if (me == 3 && got.length() < typed.length()) {
                        hc(r, c, H.GET_TERM_CHAR, VTY);
                        byte[] b = ByteBuffer.allocate(16).putLong(c.gpr[5]).putLong(c.gpr[6]).array();
                        got.append(new String(b, 0, (int) c.gpr[4], StandardCharsets.ISO_8859_1));
                    }
                    check(System.nanoTime() < end, "IPIs lost: sent " + ipis.sent + " received " + ipis.received);
                    Thread.onSpinWait();
                }
                while (me == 3 && got.length() < typed.length()) {
                    hc(r, c, H.GET_TERM_CHAR, VTY);
                    byte[] b = ByteBuffer.allocate(16).putLong(c.gpr[5]).putLong(c.gpr[6]).array();
                    got.append(new String(b, 0, (int) c.gpr[4], StandardCharsets.ISO_8859_1));
                    check(System.nanoTime() < end, "console input lost");
                }
            }));
        }
        threads.add(spawn("host-input", () -> {
            byte[] b = typed.getBytes(StandardCharsets.US_ASCII);
            for (int i = 0; i < b.length; i += 7) {
                r.console.type(Arrays.copyOfRange(b, i, Math.min(b.length, i + 7)));
            }
        }));
        joinAll(threads);
        assertEquals(ipis.sent.get(), ipis.received.get());
        assertEquals((long) n * rounds, ipis.sent.get());
        assertEquals(typed, got.toString());
        checkConsole(r.console.output(), n, records);
        for (int i = 0; i < n; i++) {
            assertEquals(0xff000000, r.m.xics().xirr(i));
            assertFalse(r.cpus[i].extIrq);
        }
    }

    /** Three CPUs send IPIs to CPU 0 as fast as they can while it takes them: one contended ICP. */
    @Test
    void ipiFanIn() throws Exception {
        Rig r = Rig.smp(4);
        startAll(r);
        Ipis ipis = new Ipis(4);
        final int perSender = 100_000;
        AtomicInteger senders = new AtomicInteger(3);
        List<Thread> threads = new ArrayList<>();
        threads.add(spawn("cpu0", () -> {
            CpuState c = r.cpu;
            check(hc(r, c, H.CPPR, 0xff) == H.SUCCESS, "H_CPPR");
            long quiet = 0;
            while (true) {
                ipis.service(r, c);
                if (senders.get() == 0 && ipis.received.get() == ipis.sent.get()) {
                    break;
                }
                if (senders.get() == 0) {
                    if (quiet == 0) {
                        quiet = System.nanoTime();
                    }
                    check(System.nanoTime() - quiet < 5_000_000_000L,
                            "IPIs lost: sent " + ipis.sent + " received " + ipis.received + " extIrq " + c.extIrq
                                    + " xirr " + Integer.toHexString(r.m.xics().xirr(0)));
                }
                Thread.onSpinWait();
            }
        }));
        for (int i = 1; i <= 3; i++) {
            CpuState c = r.cpus[i];
            threads.add(spawn("cpu" + i, () -> {
                for (int k = 0; k < perSender; k++) {
                    ipis.send(r, c, 0);
                }
                senders.decrementAndGet();
            }));
        }
        joinAll(threads);
        assertEquals(3L * perSender, ipis.received.get());
        assertFalse(r.cpu.extIrq);
        assertEquals(0xff000000, r.m.xics().xirr(0));
    }

    /**
     * CPU 0 keeps starting CPUs 2 and 3 (and flipping the interrupt endianness partition-wide);
     * they check their entry state, do some work and stop themselves; CPU 1 meanwhile takes their
     * IPIs and runs SCSI commands. A secondary may be restarted while its thread is still
     * finishing the stop-self hypercall: the entry state must survive that.
     */
    @Test
    void startStopChurn() throws Exception {
        MemoryBackend disk = pattern();
        Rig r = machine(4, disk);
        Ipis ipis = new Ipis(4);
        final int starts = 400;
        AtomicInteger done = new AtomicInteger();
        AtomicLong flips = new AtomicLong();
        List<Thread> threads = new ArrayList<>();
        // CPU 1 starts first and serves.
        assertArrayEquals(new int[] {0}, r.rtasOn(r.cpu, Rig.RTAS_BUF, "start-cpu", 1, 1, (int) START, 1));
        r.cpus[1].runPostedWork();
        threads.add(spawn("cpu1", () -> {
            CpuState c = r.cpus[1];
            check(hc(r, c, H.CPPR, 0xff) == H.SUCCESS, "H_CPPR");
            Scsi scsi = new Scsi(r, c, disk.data());
            scsi.bringUp();
            Random rnd = new Random(1);
            long end = System.nanoTime() + TIMEOUT_NS;
            while (done.get() < 3 || ipis.received.get() < ipis.sent.get()) {
                if (c.workPending) {
                    c.runPostedWork();
                }
                ipis.service(r, c);
                scsi.readAndVerify(rnd.nextInt(63));
                check(System.nanoTime() < end, "CPU 1 timed out: sent " + ipis.sent + " received " + ipis.received);
            }
            c.runPostedWork();
        }));
        for (int k = 2; k <= 3; k++) {
            CpuState c = r.cpus[k];
            int me = k;
            threads.add(spawn("cpu" + k, () -> {
                // A stand-in for the run loop: park while halted, run posted work, then "execute".
                c.runner = Thread.currentThread();
                long end = System.nanoTime() + TIMEOUT_NS;
                for (int it = 0; it < starts; it++) {
                    while (c.halted) {
                        LockSupport.parkNanos(1_000_000L);
                        check(System.nanoTime() < end, "CPU " + me + " never started (iteration " + it + ")");
                    }
                    if (c.workPending) {
                        c.runPostedWork();
                    }
                    check(c.pc == START + it * 4L, "CPU " + me + " pc " + Long.toHexString(c.pc) + " at " + it);
                    check(c.gpr[3] == me * 100_000L + it, "CPU " + me + " r3 " + c.gpr[3] + " at " + it);
                    check(!c.ceded, "started CPU still ceded");
                    ipis.send(r, c, 1);
                    check(hc(r, c, H.PUT_TERM_CHAR, VTY, 8, record(me, it), 0) == H.SUCCESS, "put");
                    // stop-self straight through the hypervisor: the return value is not ours to
                    // read once another CPU may have restarted us.
                    long b = Rig.RTAS_BUF + 0x1000L * me;
                    r.mem.write32be(b, Rig.token("stop-self"));
                    r.mem.write32be(b + 4, 0);
                    r.mem.write32be(b + 8, 0);
                    c.gpr[3] = H.KVMPPC_H_RTAS;
                    c.gpr[4] = b;
                    r.m.hcall(c);
                    // What a run loop does after any hcall: advance the PC. A host thread can be
                    // descheduled right here, long enough for CPU 0 to see this CPU stopped and
                    // restart it; make that window wide so the race actually happens.
                    if ((it & 1) == 0) {
                        LockSupport.parkNanos(200_000L);
                    }
                    c.pc = c.npc + 4;
                }
                done.incrementAndGet();
            }));
        }
        threads.add(spawn("cpu0", () -> {
            CpuState c = r.cpu;
            int[] next = new int[4];
            long end = System.nanoTime() + TIMEOUT_NS;
            while (next[2] < starts || next[3] < starts) {
                for (int k = 2; k <= 3; k++) {
                    if (next[k] >= starts) {
                        continue;
                    }
                    int[] q = r.rtasOn(c, Rig.RTAS_BUF, "query-cpu-stopped-state", 2, k);
                    check(q[0] == 0, "query status");
                    if (q[1] != 0) {
                        continue;
                    }
                    int[] st = r.rtasOn(c, Rig.RTAS_BUF, "start-cpu", 1, k, (int) (START + next[k] * 4L),
                            k * 100_000 + next[k]);
                    check(st[0] == 0, "start-cpu of a stopped CPU failed: " + st[0]);
                    next[k]++;
                }
                // Partition-wide update while CPUs come and go.
                check(hc(r, c, H.SET_MODE, flips.incrementAndGet() & 1, 4, 0, 0) == H.SUCCESS, "H_SET_MODE");
                check(System.nanoTime() < end, "CPU 0 timed out at " + next[2] + "/" + next[3]);
            }
            done.incrementAndGet();
        }));
        joinAll(threads);
        assertEquals(ipis.sent.get(), ipis.received.get());
        assertEquals(2L * starts, ipis.sent.get());
        // Every CPU ends up with the last interrupt endianness, whichever way it got it.
        for (CpuState c : r.cpus) {
            c.runPostedWork();
        }
        long ile = (flips.get() & 1) != 0 ? Spr.LPCR_ILE : 0;
        for (CpuState c : r.cpus) {
            assertEquals(ile, c.spr[Spr.LPCR] & Spr.LPCR_ILE, "ILE of CPU " + c.index);
        }
        assertTrue(r.cpus[2].halted && r.cpus[3].halted);
        checkConsole(r.console.output(), 4, new int[] {0, 0, starts, starts});
    }
}
