package org.boxtheta.javapower.papr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RAM sizes below QEMU's 256 MiB step: which sizes the configuration accepts, and the memory
 * nodes, {@code ibm,lrdr-capacity} and device tree placement they produce. Sizes that are
 * multiples of 256 MiB are compared with QEMU in {@link DeviceTreeTest}.
 */
class RamSizeTest {
    private static final long MIB = 1L << 20;

    @Test
    void acceptsMultiplesOf16MiBFrom64MiB() {
        for (long mib : new long[] {64, 80, 96, 112, 128, 192, 272, 384, 2048, 4096 + 16}) {
            assertEquals(mib * MIB, new MachineConfig().ram(mib * MIB).ramSize);
        }
        assertEquals(MachineConfig.MIN_RAM, 64 * MIB);
        assertEquals(MachineConfig.RAM_GRANULE, 16 * MIB);
    }

    @Test
    void rejectsSmallOrUnalignedSizes() {
        for (long bytes : new long[] {0, -256 * MIB, 16 * MIB, 32 * MIB, 48 * MIB, 63 * MIB, 65 * MIB, 72 * MIB,
                256 * MIB + 4096, (2048 + 8) * MIB}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new MachineConfig().ram(bytes), "size " + bytes);
            assertEquals("RAM size must be at least 64 MiB and a multiple of 16 MiB, got " + bytes + " bytes",
                    e.getMessage());
        }
        MachineConfig c = new MachineConfig();
        assertThrows(IllegalArgumentException.class, () -> c.ram(48 * MIB));
        assertEquals(2048 * MIB, c.ramSize, "a rejected size leaves the configuration unchanged");
    }

    /** The smallest machine: all of it is RMA, and the tree sits at its top like on a 2 GiB one. */
    @Test
    void smallestMachine() {
        Rig r = new Rig(new MachineConfig().ram(64 * MIB));
        assertEquals(64 * MIB, r.m.rmaSize());
        assertEquals(64 * MIB - Machine.FDT_MAX_SIZE, r.m.fdtAddress());
        Fdt.Node t = tree(r);
        assertEquals(List.of("memory@0"), memoryNodes(t));
        assertReg(t, "memory@0", 0, 64 * MIB);
        assertArrayEquals(Fdt.cells(0, (int) (64 * MIB), 0, (int) (64 * MIB), 1),
                t.find("rtas").get("ibm,lrdr-capacity"));
        // HPT for RAM/128 rounded up to a power of two, at least 2^18 (spapr_hpt_shift_for_ramsize).
        assertEquals(19, t.find("cpus/PowerPC,POWER9@0").getCell("ibm,pft-size", 1));
    }

    /** 80 MiB: a 64 MiB RMA and one 16 MiB node above it, in 16 MiB blocks. */
    @Test
    void sizeAboveAPowerOfTwo() {
        Rig r = new Rig(new MachineConfig().ram(80 * MIB));
        assertEquals(64 * MIB, r.m.rmaSize());
        assertEquals(64 * MIB - Machine.FDT_MAX_SIZE, r.m.fdtAddress());
        Fdt.Node t = tree(r);
        assertEquals(List.of("memory@4000000", "memory@0"), memoryNodes(t));
        assertReg(t, "memory@0", 0, 64 * MIB);
        assertReg(t, "memory@4000000", 64 * MIB, 16 * MIB);
        assertArrayEquals(Fdt.cells(0, (int) (80 * MIB), 0, (int) (16 * MIB), 1),
                t.find("rtas").get("ibm,lrdr-capacity"));
        assertEquals(20, t.find("cpus/PowerPC,POWER9@0").getCell("ibm,pft-size", 1));
    }

    /**
     * 448 MiB, not a multiple of 256 MiB: a 256 MiB RMA, then naturally aligned power-of-two
     * nodes (128 MiB, 64 MiB), listed highest address first as QEMU does; 64 MiB blocks.
     */
    @Test
    void sizeNotAMultipleOf256MiB() {
        Rig r = new Rig(new MachineConfig().ram(448 * MIB));
        assertEquals(256 * MIB, r.m.rmaSize());
        assertEquals(256 * MIB - Machine.FDT_MAX_SIZE, r.m.fdtAddress());
        Fdt.Node t = tree(r);
        assertEquals(List.of("memory@18000000", "memory@10000000", "memory@0"), memoryNodes(t));
        assertReg(t, "memory@0", 0, 256 * MIB);
        assertReg(t, "memory@10000000", 256 * MIB, 128 * MIB);
        assertReg(t, "memory@18000000", 384 * MIB, 64 * MIB);
        assertArrayEquals(Fdt.cells(0, (int) (448 * MIB), 0, (int) (64 * MIB), 1),
                t.find("rtas").get("ibm,lrdr-capacity"));
    }

    /**
     * The memory nodes cover the RAM exactly, in naturally aligned power-of-two pieces, and the
     * advertised block size divides it, for every accepted size up to 4 GiB and a few above 1 TiB.
     */
    @Test
    void memoryRangesCoverRam() {
        List<Long> sizes = new ArrayList<>();
        for (long ram = MachineConfig.MIN_RAM; ram <= 4096 * MIB; ram += MachineConfig.RAM_GRANULE) {
            sizes.add(ram);
        }
        sizes.add((1L << 40) + 256 * MIB);
        sizes.add((3L << 40) + 48 * MIB);
        for (long ram : sizes) {
            long next = 0;
            for (long[] range : DeviceTree.memoryRanges(ram)) {
                assertEquals(next, range[0], "gap with " + (ram >> 20) + " MiB");
                assertEquals(0, range[0] % range[1], "naturally aligned with " + (ram >> 20) + " MiB");
                assertEquals(range[1], Long.highestOneBit(range[1]), "power of two with " + (ram >> 20) + " MiB");
                next = range[0] + range[1];
            }
            assertEquals(ram, next, "memory ends at the RAM size for " + (ram >> 20) + " MiB");
            assertEquals(Math.min(Long.highestOneBit(ram), 1L << 40), DeviceTree.memoryRanges(ram).get(0)[1]);
            long lmb = DeviceTree.lmbSize(ram);
            assertEquals(0, ram % lmb);
            assertEquals(ram % (256 * MIB) == 0 ? 256 * MIB : Long.lowestOneBit(ram), lmb);
        }
    }

    private static Fdt.Node tree(Rig r) {
        return Fdt.parse(r.readBytes(r.m.fdtAddress(), r.mem.read32be(r.m.fdtAddress() + 4)));
    }

    private static List<String> memoryNodes(Fdt.Node t) {
        List<String> names = new ArrayList<>();
        for (Fdt.Node n : t.children()) {
            if (n.name().startsWith("memory@")) {
                names.add(n.name());
            }
        }
        return names;
    }

    private static long reg(Fdt.Node n, int i) {
        return ((long) n.getCell("reg", 2 * i) << 32) | (n.getCell("reg", 2 * i + 1) & 0xffffffffL);
    }

    private static void assertReg(Fdt.Node t, String node, long base, long size) {
        Fdt.Node n = t.find(node);
        assertEquals(base, reg(n, 0), node + " base");
        assertEquals(size, reg(n, 1), node + " size");
    }
}
