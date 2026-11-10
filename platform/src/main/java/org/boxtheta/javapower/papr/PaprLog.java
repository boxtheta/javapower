package org.boxtheta.javapower.papr;

import java.io.PrintStream;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Diagnostics of one machine. Everything a booting guest touches that is unimplemented,
 * suspicious or otherwise interesting for integration goes through the {@link Machine}'s log
 * ({@link Machine#log()}), so the embedder can route it per machine: set a sink with
 * {@link MachineConfig#logSink}, or later with {@link #setSink}.
 *
 * <p>{@link #unimplemented} and {@link #once} report each distinct key only once per log, but count
 * every occurrence; {@link #counts()} and {@link #unimplementedCounts()} return those counts (the
 * CLI lists the unimplemented hypercalls and RTAS calls a guest made from them).
 *
 * <p>The default sink, {@link #systemLogger()}, forwards to the {@link System.Logger} named
 * {@value #LOGGER_NAME}, so diagnostics follow the host application's logging configuration.
 * {@link #printTo} gives the plain one-line-per-message format the CLI prints to stderr.
 *
 * <p>Thread safety: every method may be called from any thread. The sink is called on the thread
 * that logs (CPU threads, console threads, the thread calling {@link Machine#pump}), possibly from
 * several threads at once and while machine or device locks are held: it must be thread-safe, must
 * not block for long and must not call back into the machine.
 */
public final class PaprLog {
    /** Name of the {@link System.Logger} the default sink writes to. */
    public static final String LOGGER_NAME = "org.boxtheta.javapower.papr";

    /** Severity of a message. */
    public enum Level {
        /** Tracing detail; not shown by {@link #printTo}. */
        DEBUG,
        /** Progress worth knowing about (e.g. the CAS outcome). */
        INFO,
        /** Something went wrong or looks wrong, on the guest's or the host's side. */
        WARN,
        /** The guest asked for something the emulator does not implement. */
        UNIMP
    }

    /**
     * A single log record.
     *
     * @param level   severity
     * @param key     for {@link #once} and {@link #unimplemented}, the key the message is counted
     *                under (for example {@code "hcall-0x2f8"}); null for the other messages
     * @param message the text, which for keyed messages starts with the key
     */
    public record Entry(Level level, String key, String message) {
        public Entry {
            Objects.requireNonNull(level);
            Objects.requireNonNull(message);
        }
    }

    private record Tally(Level level, AtomicLong count) {}

    private volatile Consumer<Entry> sink;
    private final ConcurrentHashMap<String, Tally> counts = new ConcurrentHashMap<>();

    /** A log with the default sink, {@link #systemLogger()}. */
    public PaprLog() {
        this(null);
    }

    /** A log writing to {@code sink}; null means the default, {@link #systemLogger()}. */
    public PaprLog(Consumer<Entry> sink) {
        this.sink = sink != null ? sink : systemLogger();
    }

    /** Sink that forwards to the {@link System.Logger} named {@value #LOGGER_NAME}. */
    public static Consumer<Entry> systemLogger() {
        System.Logger logger = System.getLogger(LOGGER_NAME);
        return e -> {
            System.Logger.Level l = switch (e.level()) {
                case DEBUG -> System.Logger.Level.DEBUG;
                case INFO -> System.Logger.Level.INFO;
                case WARN, UNIMP -> System.Logger.Level.WARNING;
            };
            if (logger.isLoggable(l)) {
                logger.log(l, e.level() == Level.UNIMP ? "unimplemented: " + e.message() : e.message());
            }
        };
    }

    /**
     * Sink that prints each message except {@link Level#DEBUG} as one line,
     * {@code [papr <level>] <message>}, on {@code out} (the CLI uses {@code System.err}).
     */
    public static Consumer<Entry> printTo(PrintStream out) {
        Objects.requireNonNull(out);
        return e -> {
            if (e.level() != Level.DEBUG) {
                out.println("[papr " + e.level().name().toLowerCase(java.util.Locale.ROOT) + "] " + e.message());
            }
        };
    }

    /** Replace the sink (null restores the default). Returns the previous one. */
    public Consumer<Entry> setSink(Consumer<Entry> s) {
        Consumer<Entry> old = sink;
        sink = s != null ? s : systemLogger();
        return old;
    }

    /** The current sink. */
    public Consumer<Entry> sink() {
        return sink;
    }

    public void debug(String msg) {
        sink.accept(new Entry(Level.DEBUG, null, msg));
    }

    public void info(String msg) {
        sink.accept(new Entry(Level.INFO, null, msg));
    }

    public void warn(String msg) {
        sink.accept(new Entry(Level.WARN, null, msg));
    }

    /** Something the guest asked for that we do not implement; reported once per key, counted always. */
    public void unimplemented(String key, String detail) {
        keyed(Level.UNIMP, key, detail);
    }

    /** A guest-error style warning; reported once per key, counted always. */
    public void once(String key, String detail) {
        keyed(Level.WARN, key, detail);
    }

    private void keyed(Level level, String key, String detail) {
        Tally t = counts.computeIfAbsent(key, k -> new Tally(level, new AtomicLong()));
        if (t.count().incrementAndGet() == 1L) {
            sink.accept(new Entry(level, key, key + (detail.isEmpty() ? "" : ": " + detail)));
        }
    }

    /** Snapshot of every key seen so far ({@link #once} and {@link #unimplemented}) with its count, by key. */
    public Map<String, Long> counts() {
        Map<String, Long> m = new TreeMap<>();
        counts.forEach((k, t) -> m.put(k, t.count().get()));
        return Collections.unmodifiableMap(m);
    }

    /**
     * Snapshot of the {@link #unimplemented} keys seen so far with their counts, by key: hypercalls
     * ({@code hcall-0x...}), RTAS tokens ({@code rtas-token-0x...}) and other unsupported requests.
     */
    public Map<String, Long> unimplementedCounts() {
        Map<String, Long> m = new TreeMap<>();
        counts.forEach((k, t) -> {
            if (t.level() == Level.UNIMP) {
                m.put(k, t.count().get());
            }
        });
        return Collections.unmodifiableMap(m);
    }

    /** Forget the keys, so each is reported again on its next occurrence. */
    public void resetCounts() {
        counts.clear();
    }
}
