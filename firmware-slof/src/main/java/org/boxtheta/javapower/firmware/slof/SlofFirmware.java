package org.boxtheta.javapower.firmware.slof;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;
import java.util.zip.GZIPInputStream;

/**
 * The SLOF (Slimline Open Firmware) image for the javapower {@code pseries} machine, built from
 * the SLOF sources (board {@code qemu}) by this artifact's reproducible build. The one change to
 * the upstream source is the product name in the boot banner, which reads
 * {@code Javapower Starting}. SLOF is BSD licensed; see {@code META-INF/LICENSE-SLOF} in this jar.
 *
 * <p>Pass the image to the machine configuration:
 * <pre>{@code
 * MachineConfig cfg = new MachineConfig().firmware(SlofFirmware.image());
 * }</pre>
 *
 * <p>Nothing is cached: every call to {@link #image} decompresses the bundled image again (about
 * 1 MB) and returns a fresh array the caller owns.
 */
public final class SlofFirmware {
    /** Maven coordinates of the artifact this class ships in. */
    public static final String ARTIFACT = "org.boxtheta.javapower:javapower-firmware-slof";

    static final String IMAGE_RESOURCE = "slof.bin.gz";
    static final String PROVENANCE_RESOURCE = "slof.properties";

    private SlofFirmware() {}

    /**
     * The decompressed firmware image, to be loaded at real address 0 (QEMU's {@code -bios}).
     * Its SHA-256 is checked against the build record before it is returned.
     *
     * @throws IllegalStateException if the bundled image is missing or corrupt (a packaging error)
     */
    public static byte[] image() {
        Properties p = provenance();
        byte[] image;
        try (InputStream raw = open(IMAGE_RESOURCE); InputStream in = new GZIPInputStream(raw, 64 * 1024)) {
            image = in.readAllBytes();
        } catch (IOException e) {
            // ZipException (corrupt data, CRC mismatch) or EOFException (truncated).
            throw new IllegalStateException("bundled SLOF image is corrupt", e);
        }
        if (image.length != Long.parseLong(p.getProperty("image.size"))
                || !sha256(image).equals(p.getProperty("image.sha256"))) {
            throw new IllegalStateException("bundled SLOF image fails its SHA-256 check");
        }
        return image;
    }

    /** The SLOF version the image was built from, the upstream tag (e.g. {@code qemu-slof-20260627}). */
    public static String version() {
        return provenance().getProperty("slof.tag");
    }

    /** The build record: SLOF tag and commit, toolchain, base image, sizes and SHA-256 sums. */
    static Properties provenance() {
        Properties p = new Properties();
        try (InputStream in = open(PROVENANCE_RESOURCE)) {
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }

    static byte[] resource(String name) {
        try (InputStream in = open(name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream open(String name) {
        InputStream in = SlofFirmware.class.getResourceAsStream(name);
        if (in == null) {
            throw new IllegalStateException("resource " + name + " missing from " + ARTIFACT);
        }
        return in;
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e); // every Java platform has SHA-256
        }
    }
}
