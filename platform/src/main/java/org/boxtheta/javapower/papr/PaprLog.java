package org.boxtheta.javapower.papr;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The one logging facade of the platform module. Everything a booting guest touches that is
 * unimplemented, suspicious or otherwise interesting for integration goes through here, so the
 * application can route it (stderr, a file, a test collector) with {@link #setSink}.
 *
 * <p>{@link #unimplemented} and {@link #once} report each distinct key only once, but count every
 * occurrence; {@link #touched()} returns those counts.
 */
public final class PaprLog {
    private PaprLog() {}

    /** Severity of a message. */
    public enum Level { DEBUG, INFO, WARN, UNIMP }

    /** A single log record. */
    public record Entry(Level level, String message) {}

    private static volatile Consumer<Entry> sink = e -> {
        if (e.level() != Level.DEBUG) {
            System.err.println("[papr " + e.level().name().toLowerCase() + "] " + e.message());
        }
    };
    private static final Map<String, Long> COUNTS = new ConcurrentHashMap<>();

    /** Replace the sink. Returns the previous one so tests can restore it. */
    public static Consumer<Entry> setSink(Consumer<Entry> s) {
        Consumer<Entry> old = sink;
        sink = s;
        return old;
    }

    public static void debug(String msg) {
        sink.accept(new Entry(Level.DEBUG, msg));
    }

    public static void info(String msg) {
        sink.accept(new Entry(Level.INFO, msg));
    }

    public static void warn(String msg) {
        sink.accept(new Entry(Level.WARN, msg));
    }

    /** Something the guest asked for that we do not implement; reported once per key. */
    public static void unimplemented(String key, String detail) {
        if (COUNTS.merge(key, 1L, Long::sum) == 1L) {
            sink.accept(new Entry(Level.UNIMP, key + (detail.isEmpty() ? "" : ": " + detail)));
        }
    }

    /** A guest-error style warning reported once per key. */
    public static void once(String key, String detail) {
        if (COUNTS.merge(key, 1L, Long::sum) == 1L) {
            sink.accept(new Entry(Level.WARN, key + (detail.isEmpty() ? "" : ": " + detail)));
        }
    }

    /** Snapshot of all once-keys seen so far with their occurrence counts. */
    public static Map<String, Long> touched() {
        return Map.copyOf(COUNTS);
    }

    public static Set<String> touchedKeys() {
        return Set.copyOf(COUNTS.keySet());
    }

    /** Forget the once-keys (tests). */
    public static void resetOnce() {
        COUNTS.clear();
    }
}
