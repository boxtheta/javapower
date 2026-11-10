package javapower.papr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** RTAS through KVMPPC_H_RTAS, with results as QEMU's handlers produce them. */
class RtasTest {
    static Rig fixedClock() {
        return new Rig(new MachineConfig().ram(256L << 20)
                .clock(Clock.fixed(Instant.parse("2026-11-10T12:34:56.789Z"), ZoneOffset.UTC)));
    }

    @Test
    void displayCharacterAndLegacyToken() {
        Rig r = new Rig();
        assertArrayEquals(new int[] {0}, r.rtas("display-character", 1, 'H'));
        assertArrayEquals(new int[] {0}, r.rtas(0xa, 1, 'i'));
        assertEquals("Hi", r.console.output());
    }

    @Test
    void unknownTokenFailsTheHcall() {
        Rig r = new Rig();
        long b = Rig.RTAS_BUF;
        r.mem.write32be(b, 0x3000);
        r.mem.write32be(b + 4, 0);
        r.mem.write32be(b + 8, 1);
        assertEquals(H.PARAMETER, r.hcall(H.KVMPPC_H_RTAS, b));
        assertEquals(-3, r.mem.read32be(b + 12));
        // The top four bits of the buffer address are ignored (ppc64_phys_to_real).
        r.mem.write32be(b, Rig.token("event-scan"));
        r.mem.write32be(b + 4, 4);
        assertEquals(H.SUCCESS, r.hcall(H.KVMPPC_H_RTAS, b | 0xc000000000000000L));
        assertEquals(1, r.mem.read32be(b + 12 + 16));
    }

    @Test
    void timeOfDay() {
        Rig r = fixedClock();
        assertArrayEquals(new int[] {0, 2026, 11, 10, 12, 34, 56, 789000000}, r.rtas("get-time-of-day", 8));
        assertEquals(-3, r.rtas("get-time-of-day", 7)[0]);
        assertArrayEquals(new int[] {0}, r.rtas("set-time-of-day", 1, 2001, 2, 3, 4, 5, 6, 0));
        // The offset is taken against the host clock, so a fixed clock now reads whole seconds.
        assertArrayEquals(new int[] {0, 2001, 2, 3, 4, 5, 6, 0}, r.rtas("get-time-of-day", 8));
        // Out-of-range fields are normalised like mktimegm.
        assertArrayEquals(new int[] {0}, r.rtas("set-time-of-day", 1, 2001, 13, 1, 0, 0, 0, 0));
        assertEquals(2002, r.rtas("get-time-of-day", 8)[1]);
        assertEquals(-3, r.rtas("set-time-of-day", 1, 2001, 2, 3, 4, 5, 6)[0]);
    }

    @Test
    void powerOffAndReboot() {
        Rig r = new Rig();
        assertEquals(-3, r.rtas("power-off", 1, -1)[0]);
        assertEquals(Machine.ShutdownReason.NONE, r.m.shutdownReason());
        assertArrayEquals(new int[] {0}, r.rtas("power-off", 1, -1, -1));
        assertTrue(r.cpu.stopRequested);
        assertEquals(Machine.ShutdownReason.POWER_OFF, r.m.shutdownReason());

        r.cpu.stopRequested = false;
        r.m.reset(r.cpu);
        assertEquals(Machine.ShutdownReason.NONE, r.m.shutdownReason());
        assertEquals(-3, r.rtas("system-reboot", 1, 0)[0]);
        assertArrayEquals(new int[] {0}, r.rtas("system-reboot", 1));
        assertTrue(r.cpu.stopRequested);
        assertEquals(Machine.ShutdownReason.REBOOT, r.m.shutdownReason());
    }

    @Test
    void osTermStopsWithMessage() {
        Rig r = new Rig();
        r.writeBytes(0x40000, "Kernel panic - not syncing\0".getBytes(StandardCharsets.US_ASCII));
        assertArrayEquals(new int[] {0}, r.rtas("ibm,os-term", 1, 0x40000));
        assertEquals(Machine.ShutdownReason.PANIC, r.m.shutdownReason());
        assertEquals("Kernel panic - not syncing", r.m.shutdownMessage());
    }

    @Test
    void cpuCalls() {
        Rig r = new Rig();
        assertArrayEquals(new int[] {0, 2}, r.rtas("query-cpu-stopped-state", 2, 0));
        assertEquals(-3, r.rtas("query-cpu-stopped-state", 2, 1)[0]);
        assertEquals(-3, r.rtas("query-cpu-stopped-state", 1, 0)[0]);
        assertEquals(-1, r.rtas("start-cpu", 1, 0, 0x100, 0)[0]);
        assertEquals(-3, r.rtas("start-cpu", 1, 1, 0x100, 0)[0]);
    }

    @Test
    void systemParameters() {
        Rig r = new Rig();
        assertEquals(0, r.rtas("ibm,get-system-parameter", 1, 20, 0x50000, 256)[0]);
        int len = r.mem.read16be(0x50000);
        String v = new String(r.readBytes(0x50002, len), StandardCharsets.US_ASCII);
        assertEquals("MaxEntCap=1,DesMem=256,DesProcs=1,MaxPlatProcs=1\0", v);
        assertEquals(0, r.rtas("ibm,get-system-parameter", 1, 42, 0x50000, 256)[0]);
        assertEquals(1, r.mem.read16be(0x50000));
        assertEquals(0, r.mem.read8(0x50002));
        assertEquals(0, r.rtas("ibm,get-system-parameter", 1, 48, 0x50000, 256)[0]);
        assertEquals(0, r.mem.read16be(0x50000));
        assertEquals(-9999, r.rtas("ibm,get-system-parameter", 1, 20, 0x50000, 1)[0]);
        assertEquals(-3, r.rtas("ibm,get-system-parameter", 1, 7, 0x50000, 256)[0]);
        assertEquals(-9002, r.rtas("ibm,set-system-parameter", 1, 20, 0x50000)[0]);
        assertEquals(-3, r.rtas("ibm,set-system-parameter", 1, 7, 0x50000)[0]);

        Rig u = new Rig(new MachineConfig().ram(256L << 20).uuid("00112233-4455-6677-8899-aabbccddeeff"));
        assertEquals(0, u.rtas("ibm,get-system-parameter", 1, 48, 0x50000, 256)[0]);
        assertEquals(16, u.mem.read16be(0x50000));
        assertEquals(0x00112233, u.mem.read32be(0x50002));
    }

    @Test
    void nvram() {
        Rig r = new Rig();
        r.writeBytes(0x60000, "hello".getBytes(StandardCharsets.US_ASCII));
        assertArrayEquals(new int[] {0, 5}, r.rtas("nvram-store", 2, 100, 0x60000, 5));
        assertArrayEquals(new int[] {0, 5}, r.rtas("nvram-fetch", 2, 98, 0x61000, 5));
        assertArrayEquals(new byte[] {0, 0, 'h', 'e', 'l'}, r.readBytes(0x61000, 5));
        assertArrayEquals(new int[] {-3, 0}, r.rtas("nvram-fetch", 2, 0xfffe, 0x61000, 5));
        assertEquals(-3, r.rtas("nvram-store", 2, 0x10000, 0x61000, 1)[0]);
        assertEquals(-3, r.rtas("nvram-fetch", 1, 0, 0x61000, 1)[0]);
        assertEquals(0x10000, r.m.nvram().size());
    }

    @Test
    void eventsAndPower() {
        Rig r = new Rig();
        assertEquals(1, r.rtas("event-scan", 1, 0, 0, 0, 0)[0]);
        assertEquals(-3, r.rtas("event-scan", 1, 0, 0, 0)[0]);
        assertEquals(1, r.rtas("check-exception", 1, 0, 0, -1, 0, 0x70000, 1024)[0]);
        assertEquals(-3, r.rtas("check-exception", 1, 0, 0, -1)[0]);
        assertArrayEquals(new int[] {0, 100}, r.rtas("get-power-level", 2, -1));
        assertEquals(-3, r.rtas("get-power-level", 2, 3)[0]);
        assertArrayEquals(new int[] {0, 100}, r.rtas("set-power-level", 2, -1, 100));
        assertEquals(-3, r.rtas("set-power-level", 2, -1)[0]);
    }

    @Test
    void sensorsIndicatorsAndConfigureConnector() {
        Rig r = new Rig();
        assertArrayEquals(new int[] {0, 1}, r.rtas("get-sensor-state", 2, 9003, 0x10000000));
        assertArrayEquals(new int[] {0, 1}, r.rtas("get-sensor-state", 2, 9003, 0x20000000));
        assertArrayEquals(new int[] {0, 2}, r.rtas("get-sensor-state", 2, 9003, 0x20000005));
        assertArrayEquals(new int[] {0, 0}, r.rtas("get-sensor-state", 2, 9003, 0x40000008));
        assertArrayEquals(new int[] {-3, 0}, r.rtas("get-sensor-state", 2, 9003, 0x12345678));
        assertArrayEquals(new int[] {-3, 0}, r.rtas("get-sensor-state", 2, 9001, 0x10000000));
        assertEquals(-3, r.rtas("set-indicator", 1, 9001, 0x12345678, 1)[0]);
        assertEquals(-3, r.rtas("set-indicator", 1, 9999, 0x10000000, 1)[0]);
        assertEquals(0, r.rtas("set-indicator", 1, 9002, 0x10000000, 1)[0]);
        assertEquals(0, r.rtas("set-indicator", 1, 9001, 0x10000000, 1)[0]);
        // Allocating an empty logical connector fails.
        assertEquals(-3, r.rtas("set-indicator", 1, 9003, 0x20000003, 1)[0]);

        // Walk CPU 0's subtree with ibm,configure-connector.
        long wa = 0x80000;
        r.mem.write32be(wa, 0x10000000);
        int[] st = r.rtas("ibm,configure-connector", 1, (int) wa, 0);
        assertEquals(2, st[0]); // next child
        assertEquals(20, r.mem.read32be(wa + 8));
        assertEquals("PowerPC,POWER9@0", cstr(r, wa + 20));
        int props = 0;
        boolean sawReg = false;
        while (true) {
            st = r.rtas("ibm,configure-connector", 1, (int) wa, 0);
            if (st[0] != 3) {
                break;
            }
            props++;
            String name = cstr(r, wa + r.mem.read32be(wa + 8));
            if (name.equals("cpu-version")) {
                assertEquals(4, r.mem.read32be(wa + 12));
                assertEquals(0x004e1202, r.mem.read32be(wa + r.mem.read32be(wa + 16)));
                sawReg = true;
            }
        }
        assertEquals(0, st[0]); // success: subtree done
        assertTrue(sawReg);
        assertEquals(r.m.buildDeviceTree(true).find("cpus/PowerPC,POWER9@0").props().size(), props);
        r.mem.write32be(wa, 0x20000004);
        assertEquals(-9003, r.rtas("ibm,configure-connector", 1, (int) wa, 0)[0]);
        r.mem.write32be(wa, 0x0bad0000);
        assertEquals(-3, r.rtas("ibm,configure-connector", 1, (int) wa, 0)[0]);
    }

    static String cstr(Rig r, long a) {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = r.mem.read8(a++)) != 0) {
            sb.append((char) c);
        }
        return sb.toString();
    }

    @Test
    void pciCallsOnEmptyPhb() {
        Rig r = new Rig();
        assertEquals(-1, r.rtas("ibm,read-pci-config", 2, 0, 0x8000000, 0x20000000, 4)[0]);
        assertEquals(-1, r.rtas("read-pci-config", 2, 0, 4)[0]);
        assertEquals(-1, r.rtas("ibm,write-pci-config", 1, 0, 0x8000000, 0x20000000, 4, 0)[0]);
        assertEquals(-3, r.rtas("ibm,set-eeh-option", 1, 0, 0x8000000, 0x20000000, 1)[0]);
        assertEquals(-3, r.rtas("ibm,query-pe-dma-window", 6, 0, 0x8000000, 0x20000000)[0]);
    }

    @Test
    void fwnmiAndKernelDump() {
        Rig r = new Rig();
        assertEquals(-3, r.rtas("ibm,nmi-interlock", 1)[0]);
        // No linux,rtas-base in the tree yet: not supported.
        assertEquals(-3, r.rtas("ibm,nmi-register", 1, 0x7000, 0x7100)[0]);
        Fdt.Node t = r.m.buildDeviceTree(true);
        t.find("rtas").setCell("linux,rtas-base", 0x0fff0000);
        byte[] blob = Fdt.serialize(t);
        r.writeBytes(0x300000, blob);
        assertEquals(H.SUCCESS, r.hcall(H.KVMPPC_H_UPDATE_DT, 0x300000));
        assertEquals(-3, r.rtas("ibm,nmi-register", 1, 0x2000000, 0x7100)[0]);
        assertEquals(0, r.rtas("ibm,nmi-register", 1, 0x7000, 0x7100)[0]);
        assertEquals(0, r.rtas("ibm,nmi-interlock", 1)[0]);
        assertEquals(-3, r.rtas("ibm,configure-kernel-dump", 1, 2, 0)[0]);
        assertEquals(0, r.rtas("ibm,configure-kernel-dump", 1, 2, 0, 0)[0]);
        assertEquals(-1, r.rtas("ibm,configure-kernel-dump", 1, 1, 0, 0)[0]);
        assertEquals(-3, r.rtas("ibm,configure-kernel-dump", 1, 9, 0, 0)[0]);
    }
}
