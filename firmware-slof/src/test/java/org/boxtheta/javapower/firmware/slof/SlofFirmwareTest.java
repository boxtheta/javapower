package org.boxtheta.javapower.firmware.slof;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class SlofFirmwareTest {
    /** The committed compressed image is the one the build recorded. */
    @Test
    void compressedResourceMatchesRecord() {
        Properties p = SlofFirmware.provenance();
        byte[] gz = SlofFirmware.resource(SlofFirmware.IMAGE_RESOURCE);
        assertEquals(Long.parseLong(p.getProperty("compressed.size")), gz.length);
        assertEquals(p.getProperty("compressed.sha256"), SlofFirmware.sha256(gz));
    }

    /** One gzip member with no file name, comment or timestamp (RFC 1952), so builds repeat. */
    @Test
    void gzipHeaderCarriesNoBuildState() {
        byte[] gz = SlofFirmware.resource(SlofFirmware.IMAGE_RESOURCE);
        assertEquals(0x1f, gz[0] & 0xff);
        assertEquals(0x8b, gz[1] & 0xff);
        assertEquals(8, gz[2]);                       // deflate
        assertEquals(0, gz[3], "FLG: no name, comment or extra field");
        assertEquals(0, gz[4] | gz[5] | gz[6] | gz[7], "MTIME");
        // ISIZE trailer: the raw image size mod 2^32.
        int n = gz.length;
        long isize = (gz[n - 4] & 0xffL) | (gz[n - 3] & 0xffL) << 8 | (gz[n - 2] & 0xffL) << 16 | (gz[n - 1] & 0xffL) << 24;
        assertEquals(Long.parseLong(SlofFirmware.provenance().getProperty("image.size")), isize);
    }

    @Test
    void imageMatchesRecord() {
        Properties p = SlofFirmware.provenance();
        byte[] img = SlofFirmware.image();
        assertEquals(Long.parseLong(p.getProperty("image.size")), img.length);
        assertEquals(p.getProperty("image.sha256"), SlofFirmware.sha256(img));
        // ROM file system header of a SLOF board-qemu image: "header" file, "magic123".
        assertEquals("header", new String(img, 0x20, 6, StandardCharsets.US_ASCII));
        assertEquals("magic123", new String(img, 0x28, 8, StandardCharsets.US_ASCII));
    }

    @Test
    void provenanceIsComplete() {
        Properties p = SlofFirmware.provenance();
        for (String k : new String[] {"slof.tag", "slof.commit", "build.base-image", "build.apt-snapshot",
                "toolchain.gcc", "toolchain.binutils", "image.sha256", "compressed.sha256"}) {
            assertTrue(p.getProperty(k, "").length() > 0, k);
        }
        assertTrue(p.getProperty("slof.commit").matches("[0-9a-f]{40}"));
        assertTrue(p.getProperty("build.base-image").matches(".*@sha256:[0-9a-f]{64}"));
    }

    @Test
    void versionIsTheTagAndIsInTheImage() {
        String v = SlofFirmware.version();
        assertTrue(v.startsWith("qemu-slof-"), v);
        String banner = new String(SlofFirmware.image(), StandardCharsets.ISO_8859_1);
        assertTrue(banner.contains("FW Version = " + v), "version string not in the image");
    }

    @Test
    void eachCallReturnsAFreshArray() {
        byte[] a = SlofFirmware.image();
        byte[] b = SlofFirmware.image();
        assertNotSame(a, b);
        assertArrayEquals(a, b);
        Arrays.fill(a, (byte) 0);
        assertArrayEquals(b, SlofFirmware.image());
    }
}
