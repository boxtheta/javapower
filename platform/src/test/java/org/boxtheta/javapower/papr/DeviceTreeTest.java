package org.boxtheta.javapower.papr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.boxtheta.javapower.core.Msr;
import org.boxtheta.javapower.core.Spr;
import org.junit.jupiter.api.Test;

/**
 * The device trees against blobs captured from QEMU 11.1 ({@code -M pseries,ic-mode=xics,usb=off
 * -cpu power9 -smp 1 -m 2G -nodefaults}, one spapr-vty, one spapr-vscsi): the reset tree
 * ({@code pseries-min.dtb}) and the trees H_CAS returned to openSUSE's and FreeBSD's
 * {@code ibm,client-architecture-support} calls (captured with {@code dumpdtb} right after CAS).
 *
 * <p>Two normalisations: {@code rng-seed} is random, and the order of the {@code ibm,drc-*}
 * arrays is QEMU's hash-table iteration order (we emit connectors sorted by index); both arrays
 * are compared as sets of (index, name, type, power-domain) tuples.
 */
class DeviceTreeTest {
    static final long CAS_VEC = 0x200000;
    static final long CAS_BUF = 0x400000;
    static final int CAS_BUF_SIZE = 0x100000;

    private static Rig shared;

    /** A 2 GiB machine like the reference, shared by the tests and reset before each use. */
    static synchronized Rig twoGig() {
        if (shared == null) {
            shared = new Rig(new MachineConfig().ram(2L << 30));
        }
        shared.cpu.stopRequested = false;
        shared.m.reset(shared.cpu);
        return shared;
    }

    @Test
    void resetTreeMatchesQemu() throws IOException {
        Rig r = twoGig();
        long fdt = r.m.fdtAddress();
        assertEquals(0x7fe00000L, fdt);
        int size = r.mem.read32be(fdt + 4);
        byte[] blob = r.readBytes(fdt, size);
        dump("reset.dtb", blob);
        Fdt.Node ours = Fdt.parse(blob);
        Fdt.Node ref = Fdt.parse(Rig.resource("pseries-min.dtb"));
        compare("", ref, ours);
    }

    @Test
    void cpuEntryStateMatchesQemu() {
        Rig r = twoGig();
        assertEquals(0x100, r.cpu.pc);
        assertEquals(0x7fe00000L, r.cpu.gpr[3]);
        assertEquals(0, r.cpu.gpr[1]);
        assertEquals(0, r.cpu.gpr[4]);
        assertEquals(0, r.cpu.gpr[5]);
        assertEquals(Msr.ME | Msr.FP, r.cpu.msr);
        assertEquals(0x3000, r.cpu.msr);
        assertEquals(0x3f008L, r.cpu.spr[Spr.LPCR]);
        assertEquals(0x4e1202L, r.cpu.spr[Spr.PVR]);
        assertEquals(-1L, r.cpu.spr[Machine.SPR_AMOR]);
        assertEquals(0x100000L, r.cpu.spr[Machine.SPR_PSSCR]);
        assertEquals(0x103070f1f3fL, r.cpu.spr[Machine.SPR_RPR]);
        assertEquals(0x80000000L, r.cpu.spr[Spr.MMCR0]);
        // The firmware image is at 0.
        assertEquals(0x48000000, r.mem.read32be(0));
    }

    /** Write a CAS input block: PVR list, then option vectors 1..5. */
    static void writeCasVector(Rig r, boolean isa300, byte[] ov5) {
        ByteBuffer bb = ByteBuffer.allocate(4096);
        bb.putInt(0xffff0000).putInt(0x004e0000); // raw POWER9
        bb.putInt(0xffffffff).putInt(0x0f000006); // ISA 3.1 (not supported by POWER9)
        bb.putInt(0xffffffff).putInt(0x0f000005); // ISA 3.00
        bb.putInt(0xffffffff).putInt(0x0f000004); // ISA 2.07
        bb.putInt(0x00000000).putInt(0xffffffff); // terminator
        bb.put((byte) 4); // 5 vectors
        bb.put((byte) 2).put((byte) 0).put((byte) 0).put((byte) (isa300 ? 0x80 : 0)); // OV1
        bb.put((byte) 0).put((byte) 0); // OV2
        bb.put((byte) 0).put((byte) 0); // OV3
        bb.put((byte) 0).put((byte) 0); // OV4
        bb.put((byte) (ov5.length - 1)).put(ov5); // OV5
        r.writeBytes(CAS_VEC, java.util.Arrays.copyOf(bb.array(), bb.position()));
    }

    /** Option vector 5 bytes as Linux sends them (byte n at index n-1), padded to 26 bytes. */
    static byte[] ov5(int... byteAndValue) {
        byte[] v = new byte[26];
        for (int i = 0; i < byteAndValue.length; i += 2) {
            v[byteAndValue[i] - 1] = (byte) byteAndValue[i + 1];
        }
        return v;
    }

    static byte[] doCas(Rig r) {
        long st = r.hcall(H.KVMPPC_H_CAS, CAS_VEC, CAS_BUF, CAS_BUF_SIZE);
        assertEquals(H.SUCCESS, st);
        assertEquals(1, r.mem.read32be(CAS_BUF)); // update header version
        int size = r.mem.read32be(CAS_BUF + 4 + 4);
        return r.readBytes(CAS_BUF + 4, size);
    }

    @Test
    void casTreeForLinuxMatchesQemu() throws IOException {
        Rig r = twoGig();
        // Linux asks for: DRCONF memory, FORM1+FORM2 affinity, hotplug events, HPT resize,
        // dynamic-memory-v2, radix + GTSE, XICS; plus a few bits the machine does not offer.
        writeCasVector(r, true, ov5(1, 0x80, 2, 0x20, 5, 0xa0, 6, 0x05, 22, 0x80, 24, 0x40, 25, 0x80, 26, 0x40));
        byte[] blob = doCas(r);
        dump("cas-linux.dtb", blob);
        assertEquals(blob.length, Fdt.totalSize(blob));
        compare("", Fdt.parse(Rig.resource("cas-linux.dtb")), Fdt.parse(blob));
        assertEquals(0x0f000005, r.m.compatPvr());
    }

    @Test
    void casTreeForFreeBsdMatchesQemu() throws IOException {
        Rig r = twoGig();
        // FreeBSD: radix + GTSE only, and it does not claim ISA 3.00 in OV1.
        writeCasVector(r, false, ov5(24, 0x40, 26, 0x40));
        byte[] blob = doCas(r);
        dump("cas-freebsd.dtb", blob);
        compare("", Fdt.parse(Rig.resource("cas-freebsd.dtb")), Fdt.parse(blob));
    }

    @Test
    void casRejectsXiveAndReportsMissingVectors() {
        Rig r = twoGig();
        writeCasVector(r, true, ov5(23, 0x40, 24, 0x40));
        assertEquals(H.PARAMETER, r.hcall(H.KVMPPC_H_CAS, CAS_VEC, CAS_BUF, CAS_BUF_SIZE));
        assertEquals(Machine.ShutdownReason.FATAL, r.m.shutdownReason());
        assertTrue(r.cpu.stopRequested);

        Rig r2 = twoGig();
        r2.mem.fill(CAS_VEC, 4096, (byte) 0);
        // Only a PVR list and one option vector.
        ByteBuffer bb = ByteBuffer.allocate(64);
        bb.putInt(0xffffffff).putInt(0x0f000005).putInt(0).putInt(-1).put((byte) 0).put((byte) 0).put((byte) 0);
        r2.writeBytes(CAS_VEC, bb.array());
        assertEquals(H.PARAMETER, r2.hcall(H.KVMPPC_H_CAS, CAS_VEC, CAS_BUF, CAS_BUF_SIZE));

        Rig r3 = twoGig();
        r3.mem.fill(CAS_VEC, 4096, (byte) 0);
        ByteBuffer b3 = ByteBuffer.allocate(64);
        b3.putInt(0xffffffff).putInt(0x003a0000).putInt(0).putInt(-1); // POWER5 only
        r3.writeBytes(CAS_VEC, b3.array());
        assertEquals(H.HARDWARE, r3.hcall(H.KVMPPC_H_CAS, CAS_VEC, CAS_BUF, CAS_BUF_SIZE));
    }

    @Test
    void updateDtStoresValidTreesOnly() {
        Rig r = twoGig();
        byte[] blob = Fdt.serialize(r.m.buildDeviceTree(true));
        r.writeBytes(0x300000, blob);
        assertEquals(H.SUCCESS, r.hcall(H.KVMPPC_H_UPDATE_DT, 0x300000));
        assertArrayEquals(blob, r.m.fdtBlob());
        r.mem.write32be(0x300000, 0x12345678); // bad magic
        assertEquals(H.PARAMETER, r.hcall(H.KVMPPC_H_UPDATE_DT, 0x300000));
        r.mem.write32be(0x300000, Fdt.MAGIC);
        r.mem.write32be(0x300004, blob.length * 3); // grew too much
        assertEquals(H.PARAMETER, r.hcall(H.KVMPPC_H_UPDATE_DT, 0x300000));
    }

    @Test
    void chosenOptions() {
        MachineConfig c = new MachineConfig().ram(512L << 20).kernelCmdline("console=hvc0").bootDevice("c")
                .bootMenu(true).uuid("01234567-89ab-cdef-0123-456789abcdef").name("vm1");
        Rig r = new Rig(c);
        Fdt.Node t = Fdt.parse(r.readBytes(r.m.fdtAddress(), r.mem.read32be(r.m.fdtAddress() + 4)));
        assertEquals((512L << 20) - Machine.FDT_MAX_SIZE, r.m.fdtAddress());
        Fdt.Node ch = t.find("chosen");
        assertEquals("console=hvc0", ch.getString("bootargs"));
        assertEquals("c", ch.getString("qemu,boot-device"));
        assertEquals(1, ch.getCell("qemu,boot-menu", 0));
        assertEquals("01234567-89ab-cdef-0123-456789abcdef", t.getString("system-id"));
        assertEquals("vm1", t.getString("ibm,partition-name"));
        assertEquals(22, t.find("cpus/PowerPC,POWER9@0").getCell("ibm,pft-size", 1)); // HPT = RAM/128 = 2^22
    }

    // ---- comparison --------------------------------------------------------------------------

    static void dump(String name, byte[] blob) throws IOException {
        Path dir = Path.of("build", "dt");
        Files.createDirectories(dir);
        Files.write(dir.resolve(name), blob);
    }

    static boolean isDrc(String prop) {
        return prop.startsWith("ibm,drc-") && !prop.equals("ibm,drc-index");
    }

    static void compare(String path, Fdt.Node ref, Fdt.Node ours) {
        String here = path + "/" + ref.name();
        assertEquals(ref.name(), ours.name(), "node name at " + path);
        List<String> rp = new ArrayList<>(ref.props().keySet());
        List<String> op = new ArrayList<>(ours.props().keySet());
        assertEquals(rp, op, "property names/order of " + here);
        for (Map.Entry<String, byte[]> e : ref.props().entrySet()) {
            String k = e.getKey();
            byte[] a = e.getValue();
            byte[] b = ours.props().get(k);
            if (k.equals("rng-seed")) {
                assertEquals(a.length, b.length, here + " " + k);
            } else if (isDrc(k)) {
                // compared below as tuples
            } else {
                assertArrayEquals(a, b, here + " " + k + ": expected " + hex(a) + " got " + hex(b));
            }
        }
        if (ref.has("ibm,drc-indexes")) {
            assertEquals(drcTuples(ref), drcTuples(ours), "DRC set of " + here);
        }
        List<String> rc = new ArrayList<>();
        ref.children().forEach(c -> rc.add(c.name()));
        List<String> oc = new ArrayList<>();
        ours.children().forEach(c -> oc.add(c.name()));
        assertEquals(rc, oc, "children of " + here);
        Iterator<Fdt.Node> it = ours.children().iterator();
        for (Fdt.Node c : ref.children()) {
            compare(here, c, it.next());
        }
    }

    static TreeSet<String> drcTuples(Fdt.Node n) {
        ByteBuffer idx = ByteBuffer.wrap(n.get("ibm,drc-indexes"));
        ByteBuffer pow = ByteBuffer.wrap(n.get("ibm,drc-power-domains"));
        String[] names = strings(n.get("ibm,drc-names"));
        String[] types = strings(n.get("ibm,drc-types"));
        int count = idx.getInt(0);
        assertEquals(count, pow.getInt(0));
        assertEquals(count, names.length);
        assertEquals(count, types.length);
        TreeSet<String> s = new TreeSet<>();
        for (int i = 0; i < count; i++) {
            s.add(String.format("%08x %s %s %d", idx.getInt(4 + 4 * i), names[i], types[i], pow.getInt(4 + 4 * i)));
        }
        return s;
    }

    static String[] strings(byte[] v) {
        int count = ByteBuffer.wrap(v).getInt(0);
        String[] r = new String[count];
        int p = 4;
        for (int i = 0; i < count; i++) {
            int e = p;
            while (v[e] != 0) {
                e++;
            }
            r[i] = new String(v, p, e - p, java.nio.charset.StandardCharsets.US_ASCII);
            p = e + 1;
        }
        return r;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xff));
        }
        return sb.toString();
    }
}
