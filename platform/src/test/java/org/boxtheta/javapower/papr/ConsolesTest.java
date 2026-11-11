package org.boxtheta.javapower.papr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.StateMismatchException;
import org.junit.jupiter.api.Test;

/** Consoles after the first ({@link MachineConfig#addConsole}): more vty devices. */
class ConsolesTest {
    static final int VTY0 = 0x71000001;
    static final int VTY1 = 0x71000003;
    static final int VTY2 = 0x71000004;

    /** The bytes an H_GET_TERM_CHAR returned on CPU {@code c}. */
    static byte[] got(CpuState c) {
        byte[] b = ByteBuffer.allocate(16).putLong(c.gpr[5]).putLong(c.gpr[6]).array();
        return java.util.Arrays.copyOf(b, (int) c.gpr[4]);
    }

    static String chars(Rig r) {
        return new String(got(r.cpu), StandardCharsets.ISO_8859_1);
    }

    /** A 256 MiB machine with the first console and two more, the second named "rpc". */
    static MachineConfig two(BufferCharBackend a, BufferCharBackend b) {
        MachineConfig c = new MachineConfig().ram(256L << 20);
        assertEquals(VTY1, c.addConsole(a).unitAddress());
        assertEquals(VTY2, c.addConsole(b).name("rpc").unitAddress());
        return c;
    }

    @Test
    void deviceTreeMatchesQemuWithThreeVtys() throws IOException {
        // QEMU 11.1, the reference command line plus two more -device spapr-vty (after the
        // spapr-vscsi), dumped with the monitor's dumpdtb before the first instruction.
        MachineConfig c = new MachineConfig().ram(2L << 30);
        c.addConsole(new BufferCharBackend());
        c.addConsole(new BufferCharBackend());
        Rig r = new Rig(c);
        long fdt = r.m.fdtAddress();
        byte[] blob = r.readBytes(fdt, r.mem.read32be(fdt + 4));
        DeviceTreeTest.dump("reset-3vty.dtb", blob);
        DeviceTreeTest.compare("", Fdt.parse(Rig.resource("pseries-3vty.dtb")), Fdt.parse(blob));
    }

    @Test
    void namesAreLabelsInTheDeviceTree() {
        Rig r = new Rig(two(new BufferCharBackend(), new BufferCharBackend()));
        Fdt.Node root = Fdt.parse(r.readBytes(r.m.fdtAddress(), r.mem.read32be(r.m.fdtAddress() + 4)));
        Fdt.Node vdev = root.child("vdevice");
        assertNull(vdev.child("vty@71000001").get("label"));
        assertNull(vdev.child("vty@71000003").get("label"));
        Fdt.Node named = vdev.child("vty@71000004");
        assertEquals("rpc\0", new String(named.get("label"), StandardCharsets.US_ASCII));
        assertEquals(List.of("label", "interrupts", "compatible", "device_type", "reg"),
                new ArrayList<>(named.props().keySet()));
        Fdt.Node chosen = root.child("chosen");
        assertEquals("/vdevice/vty@71000001\0", new String(chosen.get("stdout-path"), StandardCharsets.US_ASCII));
        assertEquals("/vdevice/vty@71000001\0", new String(chosen.get("linux,stdout-path"), StandardCharsets.US_ASCII));
    }

    @Test
    void machineListsItsConsoles() {
        BufferCharBackend a = new BufferCharBackend();
        BufferCharBackend b = new BufferCharBackend();
        Rig r = new Rig(two(a, b));
        List<Vty> v = r.m.vtys();
        assertEquals(3, v.size());
        assertSame(r.m.vty(), v.get(0));
        assertSame(r.console, v.get(0).backend());
        assertSame(a, v.get(1).backend());
        assertSame(b, v.get(2).backend());
        assertNull(v.get(0).name());
        assertNull(v.get(1).name());
        assertEquals("rpc", v.get(2).name());
        assertSame(v.get(2), r.m.vioDevice(VTY2, Vty.class));
        assertEquals(List.of(VTY0, VTY1, VTY2), v.stream().map(Vty::reg).toList());
        assertEquals(List.of(0x1101, 0x1103, 0x1104), v.stream().map(Vty::irq).toList());
        assertEquals(1, new Rig().m.vtys().size());
    }

    @Test
    void hypercallsGoToTheVtyAtTheUnitAddress() {
        BufferCharBackend a = new BufferCharBackend();
        BufferCharBackend b = new BufferCharBackend();
        Rig r = new Rig(two(a, b));
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TERM_CHAR, VTY0, 2, 0x3030000000000000L, 0));
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TERM_CHAR, VTY1, 2, 0x3131000000000000L, 0));
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TERM_CHAR, VTY2, 2, 0x3232000000000000L, 0));
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TERM_CHAR, 0, 1, 0x7a00000000000000L, 0)); // reg 0: the first
        assertEquals("00z", r.console.output());
        assertEquals("11", a.output());
        assertEquals("22", b.output());

        r.console.type("zero");
        a.type("one");
        b.type("two");
        assertEquals(H.SUCCESS, r.hcall(H.GET_TERM_CHAR, VTY2));
        assertEquals("two", chars(r));
        assertEquals(H.SUCCESS, r.hcall(H.GET_TERM_CHAR, VTY1));
        assertEquals("one", chars(r));
        assertEquals(H.SUCCESS, r.hcall(H.GET_TERM_CHAR, 0));
        assertEquals("zero", chars(r));
        assertEquals(H.SUCCESS, r.hcall(H.GET_TERM_CHAR, VTY1));
        assertEquals("", chars(r));

        assertEquals(H.PARAMETER, r.hcall(H.GET_TERM_CHAR, 0x71000002)); // v-scsi
        assertEquals(H.PARAMETER, r.hcall(H.PUT_TERM_CHAR, 0x71000005, 1, 0, 0)); // nothing there
    }

    @Test
    void eachVtyInterruptsOnItsOwnSource() {
        BufferCharBackend a = new BufferCharBackend();
        BufferCharBackend b = new BufferCharBackend();
        Rig r = new Rig(two(a, b));
        for (int irq : new int[] {0x1101, 0x1103, 0x1104}) {
            assertArrayEquals(new int[] {0}, r.rtas("ibm,set-xive", 1, irq, 0, 5));
        }
        assertEquals(H.SUCCESS, r.hcall(H.CPPR, 0xff));
        assertFalse(r.cpu.extIrq);
        b.type("x");
        assertTrue(r.cpu.extIrq);
        assertEquals(H.SUCCESS, r.hcall(H.XIRR));
        assertEquals(0xff001104L, r.r(4));
        // More input into a buffer that is not empty: no new interrupt.
        b.type("y");
        assertEquals(H.SUCCESS, r.hcall(H.EOI, 0xff001104L));
        assertFalse(r.cpu.extIrq);
        a.type("z");
        assertEquals(H.SUCCESS, r.hcall(H.XIRR));
        assertEquals(0xff001103L, r.r(4));
        assertEquals(H.SUCCESS, r.hcall(H.EOI, 0xff001103L));
        assertEquals(H.SUCCESS, r.hcall(H.GET_TERM_CHAR, VTY2));
        assertEquals("xy", chars(r));
        assertEquals(H.SUCCESS, r.hcall(H.GET_TERM_CHAR, VTY1));
        assertEquals("z", chars(r));
        assertFalse(r.cpu.extIrq);
    }

    /**
     * Host threads type into three consoles at once while one guest CPU per console reads it with
     * H_GET_TERM_CHAR and echoes what it read back with H_PUT_TERM_CHAR: every console's input
     * arrives whole and in order, and only on its own console.
     */
    @Test
    void concurrentInputOnSeveralConsoles() throws Exception {
        int n = 3;
        int len = 200_000;
        BufferCharBackend[] be = new BufferCharBackend[n];
        MachineConfig c = new MachineConfig().ram(256L << 20).cpus(n);
        be[0] = new BufferCharBackend();
        c.console(be[0]);
        for (int k = 1; k < n; k++) {
            be[k] = new BufferCharBackend();
            c.addConsole(be[k]);
        }
        Rig r = new Rig(c);
        int[] regs = {VTY0, VTY1, VTY2};
        byte[][] input = new byte[n][len];
        for (int k = 0; k < n; k++) {
            for (int i = 0; i < len; i++) {
                input[k][i] = (byte) ('a' + k * 8 + i % 7); // disjoint byte values per console, no CR
            }
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        ByteArrayOutputStream[] read = new ByteArrayOutputStream[n];
        for (int k = 0; k < n; k++) {
            int kk = k;
            read[k] = new ByteArrayOutputStream();
            threads.add(Thread.ofPlatform().name("host-" + k).unstarted(() -> {
                for (int i = 0; i < len; i += 1000) {
                    be[kk].type(java.util.Arrays.copyOfRange(input[kk], i, Math.min(len, i + 1000)));
                }
            }));
            threads.add(Thread.ofPlatform().name("cpu-" + k).unstarted(() -> {
                CpuState cpu = r.cpus[kk];
                long deadline = System.nanoTime() + 60_000_000_000L;
                while (read[kk].size() < len && System.nanoTime() < deadline) {
                    if (r.hcallOn(cpu, H.GET_TERM_CHAR, regs[kk]) != H.SUCCESS) {
                        throw new AssertionError("H_GET_TERM_CHAR failed");
                    }
                    byte[] b = got(cpu);
                    read[kk].write(b, 0, b.length);
                    if (b.length > 0) {
                        long hi = cpu.gpr[5];
                        long lo = cpu.gpr[6];
                        if (r.hcallOn(cpu, H.PUT_TERM_CHAR, regs[kk], b.length, hi, lo) != H.SUCCESS) {
                            throw new AssertionError("H_PUT_TERM_CHAR failed");
                        }
                    }
                }
            }));
        }
        for (Thread t : threads) {
            t.setUncaughtExceptionHandler((th, e) -> failure.compareAndSet(null, e));
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        for (int k = 0; k < n; k++) {
            assertArrayEquals(input[k], read[k].toByteArray(), "input of console " + k);
            assertArrayEquals(input[k], be[k].outputBytes(), "echo on console " + k);
            assertEquals(0, r.m.vtys().get(k).pendingInput());
        }
    }

    @Test
    void resetKeepsInputOfEveryConsole() {
        BufferCharBackend a = new BufferCharBackend();
        BufferCharBackend b = new BufferCharBackend();
        Rig r = new Rig(two(a, b));
        b.type("kept across reset, more than sixteen bytes");
        r.m.reset(r.cpu);
        assertEquals(H.SUCCESS, r.hcall(H.GET_TERM_CHAR, VTY2));
        assertEquals("kept across rese", chars(r));
        assertEquals(H.SUCCESS, r.hcall(H.GET_TERM_CHAR, VTY2));
        assertEquals("t, more than six", chars(r));
    }

    @Test
    void unreadInputOfAnAddedConsoleIsSaved() throws IOException {
        BufferCharBackend a = new BufferCharBackend();
        BufferCharBackend b = new BufferCharBackend();
        Rig r = new Rig(two(a, b));
        assertArrayEquals(new int[] {0}, r.rtas("ibm,set-xive", 1, 0x1104, 0, 5));
        b.type("0123456789abcdefghijklmnopqrstuvwxyz"); // 16 in the device buffer, the rest waiting
        a.type("on the other one");
        byte[] state = MachineStateTest.save(r.m);

        BufferCharBackend a2 = new BufferCharBackend();
        BufferCharBackend b2 = new BufferCharBackend();
        Rig s = new Rig(two(a2, b2));
        b2.type("+typed during the load");
        MachineStateTest.load(s.m, state);
        assertEquals(H.SUCCESS, s.hcall(H.CPPR, 0xff));
        assertTrue(s.cpu.extIrq, "the saved pending interrupt of vty@71000004");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            assertEquals(H.SUCCESS, s.hcall(H.GET_TERM_CHAR, VTY2));
            sb.append(chars(s));
        }
        assertEquals("0123456789abcdefghijklmnopqrstuvwxyz+typed during the load", sb.toString());
        assertEquals(H.SUCCESS, s.hcall(H.GET_TERM_CHAR, VTY1));
        assertEquals("on the other one", chars(s));
        assertEquals(0, s.m.vty().pendingInput());
    }

    @Test
    void savedInputFillsAnEmptyBufferAfterTheLoad() throws IOException {
        // Input saved while only in the backlog (the guest emptied the buffer since) moves into the
        // buffer after the load and raises the interrupt, as when it arrives.
        BufferCharBackend a = new BufferCharBackend();
        BufferCharBackend b = new BufferCharBackend();
        Rig r = new Rig(two(a, b));
        b.type("0123456789abcdef-tail");
        byte[] state = MachineStateTest.save(r.m);
        Rig s = new Rig(two(new BufferCharBackend(), new BufferCharBackend()));
        assertArrayEquals(new int[] {0}, s.rtas("ibm,set-xive", 1, 0x1104, 0, 5));
        MachineStateTest.load(s.m, state);
        assertEquals(H.SUCCESS, s.hcall(H.GET_TERM_CHAR, VTY2));
        assertEquals("0123456789abcdef", chars(s));
        assertEquals(H.SUCCESS, s.hcall(H.GET_TERM_CHAR, VTY2));
        assertEquals("-tail", chars(s));
    }

    static StateMismatchException mismatch(MachineConfig saved, MachineConfig now) throws IOException {
        byte[] state = MachineStateTest.save(new Rig(saved).m);
        Rig b = new Rig(now);
        byte[] before = MachineStateTest.save(b.m);
        StateMismatchException e = assertThrows(StateMismatchException.class, () -> MachineStateTest.load(b.m, state));
        assertArrayEquals(before, MachineStateTest.save(b.m), "nothing changed");
        return e;
    }

    static MachineConfig consoles(String... names) {
        MachineConfig c = new MachineConfig().ram(256L << 20);
        for (String n : names) {
            c.addConsole(new BufferCharBackend()).name(n.isEmpty() ? null : n);
        }
        return c;
    }

    /** A device that is not a console, to put in the place of one. */
    static final class Other extends VioDevice {
        @Override
        public String dtName() {
            return "other";
        }

        @Override
        public String dtType() {
            return "other";
        }

        @Override
        public String dtCompatible() {
            return "javapower,other";
        }
    }

    @Test
    void hardwareCheckComparesCountOrderAndNames() throws IOException {
        String m = mismatch(consoles("a", "b"), consoles("a")).getMessage();
        assertTrue(m.contains("added VIO devices: saved 2, this machine 1"), m);

        m = mismatch(consoles(), consoles("")).getMessage();
        assertTrue(m.contains("added VIO devices: saved 0, this machine 1"), m);

        m = mismatch(consoles("a", "b"), consoles("a", "c")).getMessage();
        assertTrue(m.contains("VIO device vty@71000004: identity: saved \"label=b\", this machine \"label=c\""), m);

        m = mismatch(consoles("a"), consoles("")).getMessage();
        assertTrue(m.contains("VIO device vty@71000003: identity: saved \"label=a\", this machine \"\""), m);

        MachineConfig first = consoles("a");
        first.addVioDevice(Other::new);
        MachineConfig second = new MachineConfig().ram(256L << 20);
        second.addVioDevice(Other::new);
        second.addConsole(new BufferCharBackend()).name("a");
        m = mismatch(first, second).getMessage();
        assertTrue(m.contains("VIO device 4 node: saved vty@71000003, this machine other@71000003"), m);
        assertTrue(m.contains("VIO device 5 node: saved other@71000004, this machine vty@71000004"), m);

        // The same consoles load.
        byte[] state = MachineStateTest.save(new Rig(consoles("a", "", "b")).m);
        MachineStateTest.load(new Rig(consoles("a", "", "b")).m, state);
    }

    /** A backend that records when it is started and closed. */
    static final class Recording implements CharBackend {
        final String id;
        final List<String> events;

        Recording(String id, List<String> events) {
            this.id = id;
            this.events = events;
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }

        @Override
        public void start(InputSink sink) {
            events.add("start " + id);
        }

        @Override
        public void close() {
            events.add("close " + id);
        }
    }

    /** Fails in {@link #startDevice}. */
    static final class FailsToStart extends VioDevice {
        @Override
        public String dtName() {
            return "fails";
        }

        @Override
        public String dtType() {
            return "fails";
        }

        @Override
        public String dtCompatible() {
            return "javapower,fails";
        }

        @Override
        protected void startDevice() {
            throw new IllegalStateException("cannot start");
        }
    }

    static MachineConfig recorded(List<String> events) {
        return new MachineConfig().ram(256L << 20).firmware(new byte[] {0x48, 0, 0, 0})
                .console(new Recording("first", events));
    }

    @Test
    void backendsAreStartedAndClosedByTheMachine() throws IOException {
        List<String> ev = Collections.synchronizedList(new ArrayList<>());
        MachineConfig c = recorded(ev);
        c.addConsole(new Recording("one", ev));
        c.addVioDevice(Other::new);
        c.addConsole(new Recording("two", ev));
        Machine m = new Machine(c);
        assertEquals(List.of("start first", "start one", "start two"), ev);
        m.close();
        assertEquals(List.of("start first", "start one", "start two", "close first", "close one", "close two"), ev);
        m.close();
        assertEquals(6, ev.size(), "close is idempotent");
    }

    @Test
    void failedConstructionClosesTheBackendsItStarted() {
        // A device fails to start: the consoles started before it are closed, the later one is
        // never started and is left alone.
        List<String> ev = Collections.synchronizedList(new ArrayList<>());
        MachineConfig c = recorded(ev);
        c.addConsole(new Recording("one", ev));
        c.addVioDevice(FailsToStart::new);
        c.addConsole(new Recording("two", ev));
        assertThrows(IllegalStateException.class, () -> new Machine(c));
        assertEquals(List.of("start first", "start one", "close first", "close one"), ev);

        // Construction fails before anything started: no backend is touched.
        List<String> ev2 = Collections.synchronizedList(new ArrayList<>());
        MachineConfig d = recorded(ev2);
        d.addConsole(new Recording("one", ev2));
        d.addVioDevice(() -> null);
        assertThrows(IllegalArgumentException.class, () -> new Machine(d));
        assertEquals(List.of(), ev2);
    }

    @Test
    void aBackendServesOneConsole() {
        BufferCharBackend x = new BufferCharBackend();
        MachineConfig c = new MachineConfig().console(x);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> c.addConsole(x));
        assertTrue(e.getMessage().contains("already the backend of the first console"), e.getMessage());
        BufferCharBackend y = new BufferCharBackend();
        c.addConsole(y);
        e = assertThrows(IllegalArgumentException.class, () -> c.addConsole(y));
        assertTrue(e.getMessage().contains("already the backend of the console at 0x71000003"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> c.console(y));
        assertTrue(e.getMessage().contains("already the backend of the console at 0x71000003"), e.getMessage());

        // A Vty added as a device of one's own is checked by the machine, before anything starts.
        List<String> ev = Collections.synchronizedList(new ArrayList<>());
        MachineConfig d = recorded(ev);
        Recording shared = new Recording("shared", ev);
        d.addConsole(shared);
        d.addVioDevice(() -> new Vty(shared));
        e = assertThrows(IllegalArgumentException.class, () -> new Machine(d));
        assertTrue(e.getMessage().contains("vty@71000003 and vty@71000004 have the same CharBackend"), e.getMessage());
        assertEquals(List.of(), ev);
    }

    @Test
    void consoleNamesAndLimits() {
        MachineConfig c = new MachineConfig();
        MachineConfig.Console k = c.addConsole(new BufferCharBackend()).name("oc2.rpc.0");
        assertEquals("oc2.rpc.0", k.name());
        assertThrows(IllegalArgumentException.class, () -> c.addConsole(new BufferCharBackend()).name("oc2.rpc.0"));
        for (String bad : new String[] {"", ".x", "a b", "x/y", "é", "a".repeat(64)}) {
            assertThrows(IllegalArgumentException.class, () -> k.name(bad), bad);
        }
        k.name("a".repeat(63));
        k.name(null);
        assertNull(k.name());
        k.name("oc2.rpc.0"); // its own name again

        MachineConfig d = new MachineConfig();
        for (int i = 1; i < MachineConfig.MAX_CONSOLES; i++) {
            assertEquals(VioDevice.REG_BASE + 2 + i, d.addConsole(new BufferCharBackend()).unitAddress());
        }
        assertThrows(IllegalStateException.class, () -> d.addConsole(new BufferCharBackend()));
        d.addVioDevice(Other::new); // other devices are not consoles

        MachineConfig e = new MachineConfig().ram(256L << 20).firmware(new byte[] {0x48, 0, 0, 0});
        for (int i = 1; i < MachineConfig.MAX_CONSOLES; i++) {
            e.addConsole(new BufferCharBackend());
        }
        e.addVioDevice(() -> new Vty(new BufferCharBackend()));
        IllegalArgumentException x = assertThrows(IllegalArgumentException.class, () -> new Machine(e));
        assertTrue(x.getMessage().contains("at most 8 consoles"), x.getMessage());
    }
}
