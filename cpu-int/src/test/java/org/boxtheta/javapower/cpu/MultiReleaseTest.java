package org.boxtheta.javapower.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.boxtheta.javapower.core.PhysicalMemory;
import org.junit.jupiter.api.Test;

/**
 * PhysicalMemory and RamAccess are multi-release: the build runs these tests on Java 21 (the
 * heap-array versions) and on a later Java (the MemorySegment versions). Check that the versions
 * in use are the ones for the running Java, so both are really tested.
 */
class MultiReleaseTest {
    private static final boolean SEGMENTS = Runtime.version().feature() >= 22;

    @Test
    void versionsMatchTheRunningJava() {
        Object backing = new PhysicalMemory(1 << 16).jitBacking();
        assertEquals(SEGMENTS, backing.getClass().getName().contains("MemorySegment"), backing.getClass().getName());
        assertEquals(!SEGMENTS, backing instanceof byte[][], backing.getClass().getName());
        assertEquals(SEGMENTS ? "Ljava/lang/foreign/MemorySegment;" : "[[B",
                RamAccess.TYPE.descriptorString());
    }
}
