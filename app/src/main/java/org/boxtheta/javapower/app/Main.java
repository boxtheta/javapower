package org.boxtheta.javapower.app;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.boxtheta.javapower.Emulator;
import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.Spr;
import org.boxtheta.javapower.cpu.Interpreter;
import org.boxtheta.javapower.firmware.slof.SlofFirmware;
import org.boxtheta.javapower.papr.DatagramNetBackend;
import org.boxtheta.javapower.papr.Machine;
import org.boxtheta.javapower.papr.MachineConfig;
import org.boxtheta.javapower.papr.NetBackend;
import org.boxtheta.javapower.papr.PaprLog;
import org.boxtheta.javapower.papr.StdioCharBackend;
import org.boxtheta.javapower.papr.VirtioPciDevice;
import org.boxtheta.javapower.papr.VirtioRng;

/** Command-line front end: boots a pseries guest with the console on stdin/stdout. */
public final class Main {
    private Main() {}

    /** Seed and time of day used by {@code --deterministic}, so runs repeat exactly. */
    private static final long DETERMINISTIC_SEED = 1;
    private static final Instant DETERMINISTIC_TIME = Instant.parse("2026-01-01T00:00:00Z");

    private static final String USAGE = "usage: javapower [--ram MiB] [--smp n] [--firmware slof.bin] [--disk img]"
            + " [--disk4k img] [--cdrom iso] [--clock-scale f] [--deterministic] [--icount-shift n]"
            + " [--max-insns n] [--log-interrupts] [--status-secs n] [--jit off|blocks|regions] [--no-code-sharing]"
            + " [--persistent] [--load-state file] [--save-state file]"
            + " [--console tcp:[host:]port[,name=NAME]]..."
            + " [--net dgram,local=host:port,remote=host:port[,mac=xx:xx:xx:xx:xx:xx] | --net none[,mac=...]]"
            + " [--virtio-rng] [--test-mmio addr]";

    public static void main(String[] args) throws Exception {
        MachineConfig cfg = new MachineConfig().console(new StdioCharBackend())
                .logSink(PaprLog.printTo(System.err));
        // Consoles after the first; closed at exit (the machine closes those it started).
        java.util.List<SocketConsole> sockets = new java.util.ArrayList<>();
        try {
            run(args, cfg, sockets);
        } finally {
            for (SocketConsole c : sockets) {
                c.close();
            }
        }
    }

    private static void run(String[] args, MachineConfig cfg, java.util.List<SocketConsole> sockets) throws Exception {
        Interpreter.Clock clock = Interpreter.Clock.WALL;
        long maxInsns = Long.MAX_VALUE;
        int icountShift = 0;
        double clockScale = 1.0;
        int smp = 1;
        boolean logInterrupts = false;
        int statusSecs = 0;
        String jit = "regions";
        boolean shareCode = true;
        Path firmware = null;
        boolean persistent = false;
        Path loadState = null;
        Path saveState = null;
        java.util.List<MachineConfig.Disk> disks = new java.util.ArrayList<>();
        int virtioRngs = 0;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--ram" -> cfg.ram(Long.parseLong(args[++i]) << 20);
                    case "--smp" -> smp = Integer.parseInt(args[++i]);
                    case "--firmware" -> firmware = Path.of(args[++i]);
                    case "--disk" -> disks.add(cfg.addDisk(Path.of(args[++i])));
                    case "--disk4k" -> disks.add(cfg.addDisk(Path.of(args[++i])).blockSize(4096).physicalBlockSize(4096));
                    case "--persistent" -> persistent = true;
                    case "--load-state" -> loadState = Path.of(args[++i]);
                    case "--save-state" -> saveState = Path.of(args[++i]);
                    case "--cdrom" -> cfg.addCdrom(Path.of(args[++i]));
                    case "--clock-scale" -> clockScale = Double.parseDouble(args[++i]);
                    case "--deterministic" -> clock = Interpreter.Clock.DETERMINISTIC;
                    case "--icount-shift" -> icountShift = Integer.parseInt(args[++i]);
                    case "--max-insns" -> maxInsns = Long.parseLong(args[++i]);
                    case "--log-interrupts" -> logInterrupts = true;
                    case "--status-secs" -> statusSecs = Integer.parseInt(args[++i]);
                    case "--jit" -> {
                        jit = args[++i];
                        if (!jit.equals("off") && !jit.equals("blocks") && !jit.equals("regions")) {
                            throw new IllegalArgumentException("--jit takes off, blocks or regions");
                        }
                    }
                    case "--no-code-sharing" -> shareCode = false;
                    case "--console" -> sockets.add(addConsole(cfg, args[++i]));
                    case "--net" -> addNetwork(cfg, args[++i]);
                    case "--test-mmio" -> {
                        long base = Long.decode(args[++i]);
                        cfg.addMmio("test-mmio", base, TestMmio.SIZE, new TestMmio(base)).compatible("javapower,test-mmio");
                    }
                    case "--virtio-rng" -> virtioRngs++;
                    default -> throw new IllegalArgumentException("unknown option " + args[i]);
                }
            }
            cfg.cpus(smp);
            for (MachineConfig.Disk d : disks) {
                d.persistent(persistent);
            }
            if (clock == Interpreter.Clock.DETERMINISTIC && (smp != 1 || clockScale != 1.0)) {
                throw new IllegalArgumentException("--deterministic needs --smp 1 and no --clock-scale");
            }
        } catch (RuntimeException e) {
            System.err.println("javapower: " + e.getMessage());
            System.err.println(USAGE);
            return;
        }
        // The SLOF image bundled with the CLI unless --firmware names another.
        if (firmware != null) {
            cfg.firmware(firmware);
        } else {
            cfg.firmware(SlofFirmware.image());
        }
        Long seed = null;
        if (clock == Interpreter.Clock.DETERMINISTIC) {
            // Pin every source of entropy and time of day, so the same command repeats exactly.
            cfg.random(new Random(DETERMINISTIC_SEED)).clock(Clock.fixed(DETERMINISTIC_TIME, ZoneOffset.UTC));
            seed = DETERMINISTIC_SEED;
        }
        // virtio-rng-pci devices in slots 0, 1, ...; seeded too under --deterministic.
        java.util.List<VirtioRng> rngs = new java.util.ArrayList<>();
        for (int k = 0; k < virtioRngs; k++) {
            VirtioRng rng = seed != null ? new VirtioRng(new Random(seed + 1 + k)) : new VirtioRng();
            cfg.addPciDevice(new VirtioPciDevice(rng));
            rngs.add(rng);
        }

        Emulator emulator;
        try {
            emulator = new Emulator(cfg, clock, clockScale, seed);
        } catch (OutOfMemoryError e) {
            System.err.println("javapower: " + e.getMessage());
            return;
        }
        if (saveState != null && !persistent && !disks.isEmpty()) {
            System.err.println("[javapower] warning: the disks are snapshots whose writes are discarded at exit;"
                    + " use --persistent so they match the saved state");
        }
        CountDownLatch saved = new CountDownLatch(1);
        try (Emulator emu = emulator) {
            if (loadState != null) {
                long t0 = System.nanoTime();
                try (InputStream in = open(loadState)) {
                    emu.loadState(in);
                } catch (IOException e) {
                    System.err.println("javapower: cannot load " + loadState + ": " + e.getMessage());
                    return;
                }
                System.err.printf("[javapower] loaded %s in %d ms%n", loadState, (System.nanoTime() - t0) / 1_000_000);
            }
            if (saveState != null) {
                // SIGTERM or SIGINT: stop the guest; the main thread saves it, then exits.
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    emu.stop();
                    try {
                        saved.await(10, TimeUnit.MINUTES);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, "javapower-save-on-exit"));
            }
            for (Interpreter interp : emu.interpreters()) {
                int n = interp.state().index;
                interp.icountShift = icountShift;
                // Compilation tiers (for debugging; results are identical either way).
                interp.compileBlocks = !jit.equals("off");
                interp.compileRegions = jit.equals("regions");
                interp.shareCode = shareCode;
                interp.unimplementedHook = (s, pc, insn, key) -> System.err.printf(
                        "[cpu%d] unimplemented %s insn=%08x pc=%x msr=%x%n", n, key, insn, pc, s.msr);
                if (logInterrupts) {
                    interp.interruptHook = (s, vector, srr0) -> System.err.printf(
                            "[cpu%d] interrupt %x srr0=%x icount=%d%n", n, vector, srr0, s.icount);
                }
            }
            Thread status = statusSecs > 0 ? startStatus(emu, statusSecs) : null;
            Machine.ShutdownReason why;
            try {
                why = emu.run(maxInsns);
            } finally {
                if (status != null) {
                    status.interrupt();
                }
            }
            Machine m = emu.machine();
            System.out.flush();
            if (saveState != null) {
                if (why == Machine.ShutdownReason.NONE) {
                    save(emu, saveState);
                } else {
                    System.err.printf("%n[javapower] guest stopped (%s): no state saved%n", why);
                }
            }
            CpuState cpu = emu.cpu();
            long total = 0;
            for (CpuState c : emu.cpus()) {
                total += c.icount;
            }
            System.err.printf("%n[javapower] stopped: reason=%s %s icount=%d (all CPUs %d) pc=%x msr=%x%n",
                    why, m.shutdownMessage() == null ? "" : m.shutdownMessage(), cpu.icount, total, cpu.pc, cpu.msr);
            if (emu.cpuCount() > 1) {
                System.err.printf("[javapower] tlb shootdowns=%d wait=%dms%n", emu.domain().tlbShootdowns(),
                        emu.domain().tlbShootdownWaitNanos() / 1_000_000);
            }
            for (int k = 0; k < rngs.size(); k++) {
                System.err.printf("[javapower] virtio-rng in PCI slot %d: %d bytes served%n", k, rngs.get(k).bytesServed());
            }
            for (Map.Entry<String, Long> e : emu.unimplementedInstructions().entrySet()) {
                System.err.printf("[javapower] unimplemented %s x%d%n", e.getKey(), e.getValue());
            }
            // Hypercalls, RTAS tokens and other platform requests the guest made that we lack.
            for (Map.Entry<String, Long> e : emu.log().unimplementedCounts().entrySet()) {
                System.err.printf("[javapower] unimplemented %s x%d%n", e.getKey(), e.getValue());
            }
        } finally {
            saved.countDown(); // also when the run or the save failed
        }
    }

    /**
     * A network device from a {@code --net} specification: {@code dgram,local=host:port,remote=host:port}
     * (one frame per UDP datagram, as QEMU's {@code -netdev dgram} with inet addresses) or
     * {@code none} (a device whose cable leads nowhere), either followed by {@code ,mac=address}.
     */
    private static void addNetwork(MachineConfig cfg, String spec) {
        String[] parts = spec.split(",");
        String local = null;
        String remote = null;
        String mac = null;
        for (int k = 1; k < parts.length; k++) {
            int eq = parts[k].indexOf('=');
            String key = eq < 0 ? parts[k] : parts[k].substring(0, eq);
            String value = eq < 0 ? "" : parts[k].substring(eq + 1);
            switch (key) {
                case "local" -> local = value;
                case "remote" -> remote = value;
                case "mac" -> mac = value;
                default -> throw new IllegalArgumentException("--net: unknown option " + parts[k]);
            }
        }
        NetBackend backend;
        switch (parts[0]) {
            case "dgram" -> {
                if (local == null || remote == null) {
                    throw new IllegalArgumentException("--net dgram needs local=host:port and remote=host:port");
                }
                try {
                    backend = new DatagramNetBackend(socketAddress(local), socketAddress(remote));
                } catch (IOException e) {
                    throw new IllegalArgumentException("--net: cannot bind " + local + ": " + e.getMessage(), e);
                }
            }
            case "none" -> {
                if (local != null || remote != null) {
                    throw new IllegalArgumentException("--net none takes no addresses");
                }
                backend = new NetBackend() {
                    @Override
                    public void transmit(byte[] frame) {
                    }

                    @Override
                    public void start(FrameSink sink) {
                    }
                };
            }
            default -> throw new IllegalArgumentException("--net takes dgram,... or none, got " + parts[0]);
        }
        MachineConfig.Network n = cfg.addNetwork(backend);
        if (mac != null) {
            n.mac(mac);
        }
    }

    /** {@code host:port} or {@code [ipv6]:port}. */
    private static InetSocketAddress socketAddress(String s) {
        int colon = s.lastIndexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("--net: not host:port: " + s);
        }
        String host = s.substring(0, colon);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        int port = Integer.parseInt(s.substring(colon + 1));
        InetSocketAddress a = new InetSocketAddress(host, port);
        if (a.isUnresolved()) {
            throw new IllegalArgumentException("--net: unknown host " + host);
        }
        return a;
    }

    /**
     * For testing only ({@code --test-mmio addr}): 4 KiB of big-endian scratch registers at
     * {@code addr}, described in the device tree as {@code /test-mmio@addr}, compatible
     * {@code javapower,test-mmio}. The word at offset 0 reads 0x4a504d4d ("JPMM") and ignores
     * stores; the rest reads back what was stored. Every access is printed to stderr, so a guest
     * check can see what reached the device.
     */
    static final class TestMmio implements org.boxtheta.javapower.core.MmioHandler {
        static final int SIZE = 0x1000;
        static final long ID = 0x4a504d4dL;
        private final long base;
        private final byte[] regs = new byte[SIZE];

        TestMmio(long base) {
            this.base = base;
            for (int k = 0; k < 4; k++) {
                regs[k] = (byte) (ID >>> (24 - 8 * k));
            }
        }

        @Override
        public synchronized long read(long offset, int size) {
            long v = 0;
            for (int k = 0; k < size; k++) {
                v = (v << 8) | (regs[(int) offset + k] & 0xff);
            }
            log("load ", offset, size, v);
            return v;
        }

        @Override
        public synchronized void write(long offset, int size, long value) {
            log("store", offset, size, value);
            for (int k = size - 1; k >= 0; k--, value >>>= 8) {
                if (offset + k >= 4) {
                    regs[(int) offset + k] = (byte) value;
                }
            }
        }

        private void log(String what, long offset, int size, long v) {
            System.err.printf("[javapower] test-mmio %s %d bytes at 0x%x: 0x%x (%s)%n", what, size, base + offset, v,
                    Thread.currentThread().getName());
        }
    }

    /**
     * {@code --console tcp:[host:]port[,name=NAME]}: another console, on a TCP port (loopback
     * unless a host is given), optionally named.
     */
    private static SocketConsole addConsole(MachineConfig cfg, String spec) {
        String[] parts = spec.split(",");
        if (!parts[0].startsWith("tcp:")) {
            throw new IllegalArgumentException("--console takes tcp:[host:]port[,name=NAME], got " + spec);
        }
        String name = null;
        for (int k = 1; k < parts.length; k++) {
            if (!parts[k].startsWith("name=")) {
                throw new IllegalArgumentException("--console: unknown option " + parts[k]);
            }
            name = parts[k].substring(5);
        }
        SocketConsole s;
        try {
            s = new SocketConsole(parts[0].substring(4));
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("--console " + spec + ": cannot listen: " + e.getMessage(), e);
        }
        try {
            MachineConfig.Console c = cfg.addConsole(s).name(name);
            System.err.printf("[javapower] console vty@%x%s on %s%n", c.unitAddress(),
                    name == null ? "" : " (" + name + ")", s.address());
        } catch (RuntimeException e) {
            s.close();
            throw e;
        }
        return s;
    }

    /** A state file, gunzipped if its name ends in .gz. */
    private static InputStream open(Path p) throws IOException {
        InputStream in = new BufferedInputStream(Files.newInputStream(p), 1 << 16);
        return p.toString().endsWith(".gz") ? new GZIPInputStream(in, 1 << 16) : in;
    }

    /** Save to a temporary file next to {@code p}, then move it into place; gzip if the name ends in .gz. */
    private static void save(Emulator emu, Path p) throws IOException {
        long t0 = System.nanoTime();
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        try (OutputStream f = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16);
                OutputStream out = p.toString().endsWith(".gz") ? new GZIPOutputStream(f, 1 << 16) : f) {
            emu.saveState(out);
        }
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        System.err.printf("%n[javapower] saved %s (%d bytes) in %d ms%n", p, Files.size(p),
                (System.nanoTime() - t0) / 1_000_000);
    }

    /**
     * Progress report every {@code secs} seconds: one line per CPU with its instruction count,
     * rate and state (run, idle after H_CEDE, or stopped), plus a total. The snapshot is racy
     * (the CPU threads keep running), which is fine for progress reports.
     */
    private static Thread startStatus(Emulator emu, int secs) {
        long periodMs = secs * 1000L;
        int n = emu.cpuCount();
        return Thread.ofPlatform().daemon().name("status").start(() -> {
            long[] last = new long[n];
            long t0 = System.nanoTime();
            try {
                while (true) {
                    Thread.sleep(periodMs);
                    long elapsed = (System.nanoTime() - t0) / 1_000_000_000L;
                    long sum = 0;
                    long dsum = 0;
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < n; i++) {
                        CpuState c = emu.cpu(i);
                        long ic = c.icount;
                        long d = ic - last[i];
                        last[i] = ic;
                        sum += ic;
                        dsum += d;
                        String state = c.halted ? "stopped" : c.ceded ? "idle" : "run";
                        sb.append(String.format("[status] cpu%d icount=%d (+%d, %.1f MIPS) %s pc=%x msr=%x lr=%x pid=%x%n",
                                i, ic, d, d / (periodMs * 1000.0), state, c.pc, c.msr, c.lr, c.spr[Spr.PIDR]));
                    }
                    if (n > 1) {
                        sb.append(String.format("[status] t=%ds all icount=%d (+%d, %.1f MIPS)%n", elapsed, sum, dsum,
                                dsum / (periodMs * 1000.0)));
                    }
                    System.err.print(sb);
                }
            } catch (InterruptedException e) {
                // exit
            }
        });
    }
}
