package org.boxtheta.javapower;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.boxtheta.javapower.core.PhysicalMemory;
import org.boxtheta.javapower.core.StateMismatchException;
import org.boxtheta.javapower.cpu.Interpreter;
import org.boxtheta.javapower.papr.BufferCharBackend;
import org.boxtheta.javapower.papr.Fdt;
import org.boxtheta.javapower.papr.Machine;
import org.boxtheta.javapower.papr.MachineConfig;
import org.boxtheta.javapower.papr.VioDevice;
import org.boxtheta.javapower.viotest.EchoDevice;
import org.junit.jupiter.api.Test;

/**
 * VIO devices added through {@link MachineConfig#addVioDevice}, written outside the platform
 * package ({@link EchoDevice}): guest code talks to one over its CRQ with DMA and interrupts, and
 * the device takes part in the device tree, reset, quiesce, saved state and the machine's life
 * cycle.
 */
class AddedVioDeviceTest {
    static final int REG = 0x71000003;
    static final int IRQ = 0x1103;

    static final int H_EOI = 0x64;
    static final int H_CPPR = 0x68;
    static final int H_XIRR = 0x74;
    static final int H_PUT_TCE = 0x20;
    static final int H_REG_CRQ = 0xfc;
    static final int H_VIO_SIGNAL = 0x104;
    static final int H_SEND_CRQ = 0x108;
    static final int H_RTAS = 0xf000;
    static final int RTAS_POWER_OFF = 0x2003;
    static final int RTAS_SET_XIVE = 0x200a;
    static final int RTAS_QUIESCE = 0x2011;

    static final int DATA = 0x20000;
    static final int SEND_STATUS = 0x00;
    static final int XIRR = 0x08;
    static final int REPLY0 = 0x10;
    static final int REPLY1 = 0x18;
    static final int PHASE = 0x20;
    static final int HOST_ENTRY = 0x28;
    static final int QUIESCE_STATUS = 0x30;
    static final int FAIL = 0x38;
    static final int SETUP_STATUS = 0x40;
    static final int XIVE_STATUS = 0x48;
    static final int RTAS_BUF = 0x10000;
    static final int CRQ = 0x30000;
    static final int BUF = 0x31000;
    static final int BUF_IOBA = 0x1000;

    /**
     * The guest side, big-endian, in real mode: map the CRQ page and a buffer page in the device's
     * TCE table, register the CRQ, enable the interrupt (H_VIO_SIGNAL, ibm,set-xive, CPPR), send a
     * request for "hello", accept the interrupt (H_XIRR, H_EOI) and keep the reply. Then set
     * PHASE to 1 and wait for an entry the host posts, call quiesce and power off.
     */
    static byte[] guest() {
        Asm a = new Asm();
        a.org(0x100).b("main");
        for (int v = 0x200; v <= 0xf00; v += 0x100) {
            a.org(v).li32(30, DATA).li(31, v).stw(31, FAIL, 30).label("spin" + v).b("spin" + v);
        }
        a.org(0x2000).label("main");
        a.li32(20, DATA).li32(22, CRQ).li(24, 0);
        a.std(24, 0, 22).std(24, 8, 22).std(24, 16, 22).std(24, 24, 22); // free CRQ entries
        a.li(3, H_PUT_TCE).li32(4, REG).li(5, 0).li32(6, CRQ | 3).sc1().or(24, 24, 3);
        a.li(3, H_PUT_TCE).li32(4, REG).li(5, BUF_IOBA).li32(6, BUF | 3).sc1().or(24, 24, 3);
        a.li(3, H_REG_CRQ).li32(4, REG).li(5, 0).li(6, 0x1000).sc1().or(24, 24, 3);
        a.li(3, H_VIO_SIGNAL).li32(4, REG).li(5, 1).sc1().or(24, 24, 3);
        a.std(24, SETUP_STATUS, 20);
        a.li32(4, RTAS_BUF).li32(5, RTAS_SET_XIVE).stw(5, 0, 4).li(5, 3).stw(5, 4, 4).li(5, 1).stw(5, 8, 4);
        a.li32(5, IRQ).stw(5, 12, 4).li(5, 0).stw(5, 16, 4).li(5, 5).stw(5, 20, 4);
        a.li32(3, H_RTAS).sc1().lwz(5, 24, 4).std(5, XIVE_STATUS, 20);
        a.li(3, H_CPPR).li(4, 0xff).sc1();
        a.li32(21, BUF).li32(5, 0x68656c6c).stw(5, 0, 21).li32(5, 0x6f000000).stw(5, 4, 21);
        a.li(3, H_SEND_CRQ).li32(4, REG).li32(5, 0x80010005).sldi(5, 5, 32).li(6, BUF_IOBA).sc1();
        a.std(3, SEND_STATUS, 20);
        a.li(3, H_XIRR).sc1().std(4, XIRR, 20);
        a.li(3, H_EOI).sc1();
        a.ld(5, 0, 22).std(5, REPLY0, 20).ld(5, 8, 22).std(5, REPLY1, 20);
        a.li(5, 1).std(5, PHASE, 20).sync();
        a.label("wait").ld(5, 16, 22).cmpdi(5, 0).beq("wait");
        a.std(5, HOST_ENTRY, 20);
        a.li32(4, RTAS_BUF).li32(5, RTAS_QUIESCE).stw(5, 0, 4).li(5, 0).stw(5, 4, 4).li(5, 1).stw(5, 8, 4);
        a.li32(3, H_RTAS).sc1().lwz(5, 12, 4).std(5, QUIESCE_STATUS, 20);
        a.li32(4, RTAS_BUF).li32(5, RTAS_POWER_OFF).stw(5, 0, 4).li(5, 2).stw(5, 4, 4).li(5, 1).stw(5, 8, 4);
        a.li(5, 0).stw(5, 12, 4).stw(5, 16, 4);
        a.li32(3, H_RTAS).sc1();
        a.label("hang").b("hang");
        return a.bytesBe();
    }

    static MachineConfig config() {
        return new MachineConfig().ram(256L << 20).firmware(guest()).console(new BufferCharBackend())
                .random(new Random(1));
    }

    static Emulator deterministic(MachineConfig cfg) throws IOException {
        return new Emulator(cfg, Interpreter.Clock.DETERMINISTIC, 1.0, 1L);
    }

    /** Run until the guest waits for the host's entry. */
    static void runToPhase1(Emulator e) {
        PhysicalMemory m = e.machine().memory();
        for (int i = 0; i < 100 && m.read64be(DATA + PHASE) != 1; i++) {
            assertEquals(Machine.ShutdownReason.NONE, e.run(10_000));
        }
        assertEquals(1, m.read64be(DATA + PHASE));
        assertEquals(0, m.read64be(DATA + FAIL), "exception vector taken");
    }

    @Test
    void guestTalksToTheDeviceOverItsCrq() throws Exception {
        MachineConfig cfg = config();
        List<EchoDevice> made = new ArrayList<>();
        assertEquals(REG, cfg.addVioDevice(() -> {
            EchoDevice d = new EchoDevice("");
            made.add(d);
            return d;
        }));
        try (Emulator e = new Emulator(cfg)) {
            Machine mach = e.machine();
            EchoDevice dev = mach.vioDevice(REG, EchoDevice.class);
            assertSame(made.get(0), dev);
            assertEquals(1, dev.starts.get());
            assertEquals(IRQ, dev.irq());
            assertNull(mach.vioDevice(0x71000001, EchoDevice.class));
            assertNotNull(mach.vioDevice(0x71000001, VioDevice.class));
            assertEquals(4, mach.vioDevices().size());
            assertSame(dev, mach.vioDevices().get(3));

            // The guest runs on its own thread; the host's entry is posted from this one.
            AtomicReference<Object> result = new AtomicReference<>();
            Thread runner = new Thread(() -> {
                try {
                    result.set(e.run());
                } catch (Throwable t) {
                    result.set(t);
                }
            });
            runner.start();
            PhysicalMemory m = mach.memory();
            long end = System.currentTimeMillis() + 20_000;
            while (m.read64be(DATA + PHASE) != 1 && System.currentTimeMillis() < end) {
                Thread.sleep(5);
            }
            assertEquals(1, m.read64be(DATA + PHASE), "guest did not reach the wait");
            assertEquals(0, dev.postFromHost(7));
            runner.join(20_000);
            assertEquals(Machine.ShutdownReason.POWER_OFF, result.get());

            assertEquals(0, m.read64be(DATA + FAIL), "exception vector taken");
            assertEquals(0, m.read64be(DATA + SETUP_STATUS));
            assertEquals(0, m.read64be(DATA + XIVE_STATUS));
            assertEquals(0, m.read64be(DATA + SEND_STATUS));
            assertEquals(IRQ, m.read64be(DATA + XIRR) & 0xffffff);
            assertEquals(0x8081000000000001L, m.read64be(DATA + REPLY0));
            assertEquals(BUF_IOBA, m.read64be(DATA + REPLY1));
            byte[] buf = new byte[6];
            m.read(BUF, buf, 0, 6);
            assertArrayEquals("HELLO\0".getBytes(StandardCharsets.US_ASCII), buf);
            assertEquals(0x8082000000000007L, m.read64be(DATA + HOST_ENTRY));
            assertEquals(0, m.read64be(DATA + QUIESCE_STATUS));
            assertEquals(1, dev.requests());
            // quiesce freed the CRQ and told the device; it was also called at the reset before the run.
            assertFalse(dev.crqRegistered());
            assertEquals(2, dev.quiesces.get());
            assertEquals(1, dev.resets.get());
            assertTrue(dev.postFromHost(8) < 0, "no CRQ after quiesce");
        }
        assertEquals(1, made.get(0).closes.get());
    }

    @Test
    void deviceTreeHasANodeForEachAddedDeviceInOrder() throws Exception {
        MachineConfig cfg = config();
        cfg.addVioDevice(() -> new EchoDevice(""));
        assertEquals(REG + 1, cfg.addVioDevice(() -> new EchoDevice("")));
        byte[] plain;
        try (Emulator e = deterministic(config())) {
            e.run(10);
            plain = e.machine().fdtBlob();
        }
        try (Emulator e = deterministic(cfg)) {
            e.run(10);
            Fdt.Node vdevice = Fdt.parse(e.machine().fdtBlob()).find("/vdevice");
            List<String> names = new ArrayList<>();
            for (Fdt.Node n : vdevice.children()) {
                names.add(n.name());
            }
            assertEquals(List.of("nvram@71000000", "vty@71000001", "v-scsi@71000002", "echo@71000003",
                    "echo@71000004"), names);
            Fdt.Node n = vdevice.child("echo@71000003");
            assertEquals(EchoDevice.COMPATIBLE, n.getString("compatible"));
            assertEquals("javapower-test", n.getString("device_type"));
            assertArrayEquals(Fdt.cells(REG), n.get("reg"));
            assertArrayEquals(Fdt.cells(IRQ, 0), n.get("interrupts"));
            assertArrayEquals(Fdt.cells(REG, 0, 0, 0, (int) EchoDevice.WINDOW), n.get("ibm,my-dma-window"));
            assertArrayEquals(Fdt.cells(2), n.get("ibm,#dma-size-cells"));
            assertArrayEquals(Fdt.cells(4), n.get("javapower,ports"));
            assertArrayEquals(Fdt.cells(IRQ + 1, 0), vdevice.child("echo@71000004").get("interrupts"));

            // Without the added nodes, the tree is the one a machine without them has.
            Fdt.Node root = Fdt.parse(e.machine().fdtBlob());
            root.find("/vdevice").children().removeIf(c -> c.name().startsWith("echo@"));
            assertArrayEquals(plain, Fdt.serialize(root));
        }
    }

    @Test
    void resetClearsTheDeviceAndTheGuestStartsAgain() throws Exception {
        MachineConfig cfg = config();
        cfg.addVioDevice(() -> new EchoDevice(""));
        try (Emulator e = deterministic(cfg)) {
            EchoDevice dev = e.machine().vioDevice(REG, EchoDevice.class);
            runToPhase1(e);
            assertEquals(1, dev.requests());
            assertTrue(dev.crqRegistered());
            e.reset();
            e.machine().memory().write64be(DATA + PHASE, 0);
            runToPhase1(e);
            assertEquals(2, dev.resets.get());
            assertEquals(1, dev.requests(), "the count restarts at reset");
            assertEquals(0x8081000000000001L, e.machine().memory().read64be(DATA + REPLY0));
        }
    }

    @Test
    void savedStateCarriesTheDeviceAndItsCrq() throws Exception {
        MachineConfig cfg = config();
        cfg.addVioDevice(() -> new EchoDevice("ports=4"));
        byte[] state;
        try (Emulator a = deterministic(cfg)) {
            runToPhase1(a);
            state = SaveStateTest.save(a);
            try (Emulator b = deterministic(cfg)) {
                SaveStateTest.load(b, state);
                SaveStateTest.assertSameMachine(a, b);
                EchoDevice db = b.machine().vioDevice(REG, EchoDevice.class);
                assertEquals(1, db.requests());
                assertTrue(db.crqRegistered());
                assertEquals(0L, db.crqAddr());
                assertEquals(0, db.postFromHost(9));
                assertEquals(Machine.ShutdownReason.POWER_OFF, b.run(1_000_000));
                assertEquals(0x8082000000000009L, b.machine().memory().read64be(DATA + HOST_ENTRY));
            }
        }

        MachineConfig other = config();
        other.addVioDevice(() -> new EchoDevice("ports=8"));
        try (Emulator e = deterministic(other)) {
            String msg = assertThrows(StateMismatchException.class, () -> SaveStateTest.load(e, state)).getMessage();
            assertTrue(msg.contains("VIO device echo@71000003: identity: saved \"ports=4\", this machine \"ports=8\""),
                    msg);
        }
        try (Emulator e = deterministic(config())) {
            String msg = assertThrows(StateMismatchException.class, () -> SaveStateTest.load(e, state)).getMessage();
            assertTrue(msg.contains("added VIO devices: saved 1, this machine 0"), msg);
        }
        MachineConfig two = config();
        two.addVioDevice(() -> new EchoDevice("ports=4") {
            @Override
            public String dtCompatible() {
                return "javapower,other";
            }
        });
        try (Emulator e = deterministic(two)) {
            String msg = assertThrows(StateMismatchException.class, () -> SaveStateTest.load(e, state)).getMessage();
            assertTrue(msg.contains("VIO device echo@71000003: compatible: saved javapower,echo, this machine"
                    + " javapower,other"), msg);
        }

        // A state of a machine without added devices does not load into one with them.
        byte[] plain;
        try (Emulator e = deterministic(config())) {
            e.run(1000);
            plain = SaveStateTest.save(e);
        }
        try (Emulator e = deterministic(cfg)) {
            String msg = assertThrows(StateMismatchException.class, () -> SaveStateTest.load(e, plain)).getMessage();
            assertTrue(msg.contains("added VIO devices: saved 0, this machine 1"), msg);
        }
    }

    @Test
    void aDeviceBelongsToOneMachine() throws Exception {
        EchoDevice shared = new EchoDevice("");
        MachineConfig cfg = config();
        cfg.addVioDevice(() -> shared);
        try (Emulator e = new Emulator(cfg)) {
            IllegalStateException x = assertThrows(IllegalStateException.class, () -> new Emulator(cfg));
            assertTrue(x.getMessage().contains("already attached"), x.getMessage());
            assertEquals(0, shared.closes.get(), "the failed machine must not close another machine's device");
            assertSame(shared, e.machine().vioDevice(REG, EchoDevice.class));
        }
        assertEquals(1, shared.closes.get());

        MachineConfig nul = config();
        nul.addVioDevice(() -> null);
        assertThrows(IllegalArgumentException.class, () -> new Emulator(nul));
    }

    @Test
    void failedConstructionClosesTheAddedDevices() throws Exception {
        List<EchoDevice> made = new ArrayList<>();
        MachineConfig cfg = config();
        cfg.addVioDevice(() -> {
            EchoDevice d = new EchoDevice("");
            made.add(d);
            return d;
        });
        cfg.addDisk(Path.of("does-not-exist.img"));
        assertThrows(IOException.class, () -> new Emulator(cfg));
        assertEquals(1, made.get(0).closes.get());
        assertEquals(0, made.get(0).starts.get());

        made.clear();
        MachineConfig failing = config();
        failing.addVioDevice(() -> {
            EchoDevice d = new EchoDevice("");
            made.add(d);
            return d;
        });
        failing.addVioDevice(() -> {
            EchoDevice d = new EchoDevice("") {
                @Override
                protected void startDevice() {
                    throw new IllegalStateException("cannot start");
                }
            };
            made.add(d);
            return d;
        });
        assertThrows(IllegalStateException.class, () -> new Emulator(failing));
        assertEquals(2, made.size());
        assertEquals(1, made.get(0).starts.get());
        assertEquals(1, made.get(0).closes.get());
        assertEquals(1, made.get(1).closes.get());
    }

    @Test
    void unitAddressesAreLimited() {
        MachineConfig cfg = config();
        for (int i = 0; i < MachineConfig.MAX_ADDED_VIO_DEVICES; i++) {
            assertEquals(REG + i, cfg.addVioDevice(() -> new EchoDevice("")));
        }
        assertThrows(IllegalStateException.class, () -> cfg.addVioDevice(() -> new EchoDevice("")));
    }
}
