package javapower.papr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** XICS presentation and source behaviour (QEMU hw/intc/xics.c) and the vty console paths. */
class XicsVtyTest {
    static final int VTY = 0x71000001;
    static final int VTY_IRQ = 0x1101;

    static String chars(Rig r) {
        int n = (int) r.r(4);
        byte[] b = ByteBuffer.allocate(16).putLong(r.r(5)).putLong(r.r(6)).array();
        return new String(b, 0, n, StandardCharsets.ISO_8859_1);
    }

    @Test
    void vtyOutputAndArguments() {
        Rig r = new Rig();
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TERM_CHAR, VTY, 5, 0x48656c6c6f000000L, 0));
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TERM_CHAR, 0, 2, 0x2121000000000000L, 0)); // reg 0: default vty
        assertEquals("Hello!!", r.console.output());
        assertEquals(H.PARAMETER, r.hcall(H.PUT_TERM_CHAR, VTY, 17, 0, 0));
        assertEquals(H.PARAMETER, r.hcall(H.PUT_TERM_CHAR, 0x71000000, 1, 0, 0)); // nvram is no vty
        assertEquals(H.PARAMETER, r.hcall(H.GET_TERM_CHAR, 0x71000005));
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TERM_CHAR, VTY, 16, 0x4142434445464748L, 0x494a4b4c4d4e4f50L));
        assertTrue(r.console.output().endsWith("ABCDEFGHIJKLMNOP"));
    }

    @Test
    void vtyInputBuffersSixteenBytes() {
        Rig r = new Rig();
        assertEquals(H.SUCCESS, r.hcall(H.GET_TERM_CHAR, VTY));
        assertEquals(0, r.r(4));
        r.console.type("0123456789abcdefghij");
        assertEquals(H.SUCCESS, r.hcall(H.GET_TERM_CHAR, VTY));
        assertEquals("0123456789abcdef", chars(r));
        assertEquals(H.SUCCESS, r.hcall(H.GET_TERM_CHAR, VTY));
        assertEquals("ghij", chars(r));
        // A NUL right after a CR is held back for the next call.
        r.console.type(new byte[] {'\r', 0, 'x'});
        r.hcall(H.GET_TERM_CHAR, VTY);
        assertEquals("\r", chars(r));
        r.hcall(H.GET_TERM_CHAR, VTY);
        assertEquals("\0x", chars(r));
    }

    @Test
    void vtyInterruptDeliveryAndEoi() {
        Rig r = new Rig();
        // Unmasked at priority 5 on server 0, CPPR open.
        assertArrayEquals(new int[] {0}, r.rtas("ibm,set-xive", 1, VTY_IRQ, 0, 5));
        assertArrayEquals(new int[] {0, 0, 5}, r.rtas("ibm,get-xive", 3, VTY_IRQ));
        assertEquals(H.SUCCESS, r.hcall(H.CPPR, 0xff));
        assertFalse(r.cpu.extIrq);

        r.console.type("a");
        assertTrue(r.cpu.extIrq);
        assertEquals(H.SUCCESS, r.hcall(H.IPOLL, 0));
        assertEquals(0xff001101L, r.r(4));
        assertEquals(0xff, r.r(5));
        assertTrue(r.cpu.extIrq); // IPOLL does not accept

        assertEquals(H.SUCCESS, r.hcall(H.XIRR));
        assertEquals(0xff001101L, r.r(4));
        assertFalse(r.cpu.extIrq);
        assertEquals(0x05000000, r.m.xics().xirr(0)); // CPPR is now the source priority

        // More input while the handler runs: the buffer was drained? No, still "a" queued.
        r.hcall(H.GET_TERM_CHAR, VTY);
        assertEquals("a", chars(r));
        r.console.type("b"); // lands in an empty buffer: pulse, rejected (priority >= CPPR)
        assertFalse(r.cpu.extIrq);
        assertEquals(H.SUCCESS, r.hcall(H.EOI, 0xff001101L));
        // EOI restores CPPR and resends the rejected interrupt.
        assertTrue(r.cpu.extIrq);
        assertEquals(H.SUCCESS, r.hcall(H.XIRR_X));
        assertEquals(0xff001101L, r.r(4));
        r.hcall(H.GET_TERM_CHAR, VTY);
        assertEquals("b", chars(r));
        r.hcall(H.EOI, 0xff001101L);
        assertFalse(r.cpu.extIrq);

        // Masked: input is remembered and delivered when unmasked.
        assertArrayEquals(new int[] {0}, r.rtas("ibm,int-off", 1, VTY_IRQ));
        assertArrayEquals(new int[] {0, 0, 0xff}, r.rtas("ibm,get-xive", 3, VTY_IRQ));
        r.console.type("c");
        assertFalse(r.cpu.extIrq);
        assertArrayEquals(new int[] {0}, r.rtas("ibm,int-on", 1, VTY_IRQ));
        assertArrayEquals(new int[] {0, 0, 5}, r.rtas("ibm,get-xive", 3, VTY_IRQ));
        assertTrue(r.cpu.extIrq);

        // Raising CPPR above the pending priority withdraws it, lowering re-presents it.
        assertEquals(H.SUCCESS, r.hcall(H.CPPR, 3));
        assertFalse(r.cpu.extIrq);
        assertEquals(H.SUCCESS, r.hcall(H.CPPR, 0xff));
        assertTrue(r.cpu.extIrq);
    }

    @Test
    void xicsRtasArgumentChecks() {
        Rig r = new Rig();
        assertEquals(-3, r.rtas("ibm,set-xive", 1, 0x0fff, 0, 5)[0]);
        assertEquals(-3, r.rtas("ibm,set-xive", 1, VTY_IRQ, 1, 5)[0]);
        assertEquals(-3, r.rtas("ibm,set-xive", 1, VTY_IRQ, 0, 0x100)[0]);
        assertEquals(-3, r.rtas("ibm,set-xive", 1, VTY_IRQ, 0)[0]);
        assertEquals(-3, r.rtas("ibm,get-xive", 3, 0x2000)[0]);
        assertEquals(-3, r.rtas("ibm,int-on", 1, 0x2000)[0]);
        assertEquals(-3, r.rtas("ibm,int-off", 2, VTY_IRQ)[0]);
        assertEquals(H.PARAMETER, r.hcall(H.IPI, 1, 0));
        assertEquals(H.PARAMETER, r.hcall(H.IPOLL, 2));
    }

    @Test
    void ipi() {
        Rig r = new Rig();
        r.hcall(H.CPPR, 0xff);
        assertEquals(H.SUCCESS, r.hcall(H.IPI, 0, 4));
        assertTrue(r.cpu.extIrq);
        r.hcall(H.XIRR);
        assertEquals(0xff000002L, r.r(4));
        // EOI with MFRR still more favoured than CPPR re-raises the IPI.
        r.hcall(H.EOI, 0xff000002L);
        assertTrue(r.cpu.extIrq);
        r.hcall(H.XIRR);
        r.hcall(H.IPI, 0, 0xff);
        r.hcall(H.EOI, 0xff000002L);
        assertFalse(r.cpu.extIrq);
        assertEquals(H.SUCCESS, r.hcall(H.IPOLL, 0));
        assertEquals(0xff000000L, r.r(4));
        assertEquals(0xff, r.r(5));
    }

    @Test
    void levelSourceIsResentUntilDeasserted() {
        Rig r = new Rig();
        int lsi = Xics.IRQ_PCI_LSI;
        assertTrue(r.m.xics().isLsi(lsi));
        r.rtas("ibm,set-xive", 1, lsi, 0, 5);
        r.hcall(H.CPPR, 0xff);
        r.m.xics().setIrq(lsi, true);
        assertTrue(r.cpu.extIrq);
        r.hcall(H.XIRR);
        assertEquals(0xff000000L | lsi, r.r(4));
        r.hcall(H.EOI, 0xff000000L | lsi);
        assertTrue(r.cpu.extIrq); // still asserted
        r.hcall(H.XIRR);
        r.m.xics().setIrq(lsi, false);
        r.hcall(H.EOI, 0xff000000L | lsi);
        assertFalse(r.cpu.extIrq);
    }

    @Test
    void moreFavouredSourcePreemptsPending() {
        Rig r = new Rig();
        r.rtas("ibm,set-xive", 1, VTY_IRQ, 0, 6);
        r.rtas("ibm,set-xive", 1, 0x1102, 0, 2);
        r.hcall(H.CPPR, 0xff);
        r.console.type("x");
        assertEquals(0xff001101, r.m.xics().xirr(0));
        r.m.xics().pulse(0x1102);
        assertEquals(0xff001102, r.m.xics().xirr(0)); // VTY interrupt rejected, will be resent
        r.hcall(H.XIRR);
        assertEquals(0xff001102L, r.r(4));
        r.hcall(H.EOI, 0xff001102L);
        r.hcall(H.XIRR);
        assertEquals(0xff001101L, r.r(4));
    }

    @Test
    void consoleInputFromAnotherThread() throws InterruptedException {
        Rig r = new Rig();
        r.rtas("ibm,set-xive", 1, VTY_IRQ, 0, 5);
        r.hcall(H.CPPR, 0xff);
        Thread t = new Thread(() -> {
            for (int i = 0; i < 200; i++) {
                r.console.type("z");
            }
        });
        t.start();
        int got = 0;
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (got < 200 && System.nanoTime() < deadline) {
            r.hcall(H.GET_TERM_CHAR, VTY);
            got += (int) r.r(4);
        }
        t.join();
        assertEquals(200, got);
        assertEquals(0, r.m.vty().pendingInput());
    }
}
