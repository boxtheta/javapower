package javapower.papr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A dynamic-reconfiguration connector, as listed in the {@code ibm,drc-*} properties and driven by
 * the set-indicator / get-sensor-state / ibm,configure-connector RTAS calls (QEMU's
 * hw/ppc/spapr_drc.c). Nothing is ever hot-plugged on this machine, so the model only needs the
 * states a booted guest can observe: populated connectors (CPU 0, PHB 0) are configured, empty ones
 * are unusable (logical) or powered on but empty (physical).
 */
final class Drc {
    enum Kind {
        CPU(0x10000000, "CPU", false),
        PHB(0x20000000, "PHB", false),
        PCI(0x40000000, "28", true);

        final int base;
        final String typeName;
        final boolean physical;

        Kind(int base, String typeName, boolean physical) {
            this.base = base;
            this.typeName = typeName;
            this.physical = physical;
        }
    }

    // States (PAPR+ 13.4), numbered like QEMU's SpaprDrcState.
    static final int STATE_PHYSICAL_POWERON = 2;
    static final int STATE_PHYSICAL_UNISOLATE = 3;
    static final int STATE_PHYSICAL_CONFIGURED = 4;
    static final int STATE_LOGICAL_UNUSABLE = 5;
    static final int STATE_LOGICAL_AVAILABLE = 6;
    static final int STATE_LOGICAL_UNISOLATE = 7;
    static final int STATE_LOGICAL_CONFIGURED = 8;

    // Entity sense values.
    static final int SENSE_EMPTY = 0;
    static final int SENSE_PRESENT = 1;
    static final int SENSE_UNUSABLE = 2;

    final Kind kind;
    final int id;
    /** Device tree fragment handed out by ibm,configure-connector; null if nothing is attached. */
    private final Supplier<Fdt.Node> fragment;
    int state;
    int drIndicator;

    /** configure-connector walk over the fragment, flattened into tag events. */
    private List<Object[]> ccsEvents;
    private int ccsPos;

    Drc(Kind kind, int id, Supplier<Fdt.Node> fragment) {
        this.kind = kind;
        this.id = id;
        this.fragment = fragment;
        reset();
    }

    int index() {
        return kind.base | id;
    }

    String name() {
        return switch (kind) {
            case CPU -> "CPU " + id;
            case PHB -> "PHB " + id;
            case PCI -> "C" + id;
        };
    }

    boolean populated() {
        return fragment != null;
    }

    void reset() {
        if (populated()) {
            state = kind.physical ? STATE_PHYSICAL_CONFIGURED : STATE_LOGICAL_CONFIGURED;
        } else {
            state = kind.physical ? STATE_PHYSICAL_POWERON : STATE_LOGICAL_UNUSABLE;
        }
        drIndicator = 0;
        ccsEvents = null;
        ccsPos = 0;
    }

    int entitySense() {
        if (kind.physical) {
            return populated() ? SENSE_PRESENT : SENSE_EMPTY;
        }
        if (populated()) {
            return SENSE_PRESENT;
        }
        return state == STATE_LOGICAL_UNUSABLE ? SENSE_UNUSABLE : SENSE_EMPTY;
    }

    boolean configurable() {
        return state == STATE_LOGICAL_UNISOLATE || state == STATE_PHYSICAL_UNISOLATE
                || state == STATE_LOGICAL_CONFIGURED || state == STATE_PHYSICAL_CONFIGURED;
    }

    /** Isolation-state indicator: 0 isolate, 1 unisolate. Returns an RTAS status. */
    int setIsolation(int value) {
        if (value == 1) {
            if (kind.physical) {
                if (!populated()) {
                    return Rtas.NO_SUCH_INDICATOR;
                }
                if (state == STATE_PHYSICAL_POWERON) {
                    state = STATE_PHYSICAL_UNISOLATE;
                }
                return Rtas.SUCCESS;
            }
            if (state == STATE_LOGICAL_UNISOLATE || state == STATE_LOGICAL_CONFIGURED) {
                return Rtas.SUCCESS;
            }
            if (state != STATE_LOGICAL_AVAILABLE) {
                return Rtas.NO_SUCH_INDICATOR;
            }
            state = STATE_LOGICAL_UNISOLATE;
            return Rtas.SUCCESS;
        } else if (value == 0) {
            if (kind.physical) {
                if (state == STATE_PHYSICAL_UNISOLATE || state == STATE_PHYSICAL_CONFIGURED) {
                    state = STATE_PHYSICAL_POWERON;
                }
                return Rtas.SUCCESS;
            }
            if (state == STATE_LOGICAL_UNISOLATE || state == STATE_LOGICAL_CONFIGURED) {
                state = STATE_LOGICAL_AVAILABLE;
            }
            return Rtas.SUCCESS;
        }
        return Rtas.PARAM_ERROR;
    }

    /** Allocation-state indicator (logical connectors): 0 unusable, 1 usable. */
    int setAllocation(int value) {
        if (kind.physical) {
            return Rtas.NO_SUCH_INDICATOR;
        }
        if (value == 1) {
            if (state == STATE_LOGICAL_UNUSABLE) {
                if (!populated()) {
                    return Rtas.NO_SUCH_INDICATOR;
                }
                state = STATE_LOGICAL_AVAILABLE;
            }
            return Rtas.SUCCESS;
        } else if (value == 0) {
            if (state == STATE_LOGICAL_AVAILABLE) {
                state = STATE_LOGICAL_UNUSABLE;
            }
            return Rtas.SUCCESS;
        }
        return Rtas.PARAM_ERROR;
    }

    /** One step of ibm,configure-connector; returns the event (tag, name, value) or null when done. */
    Object[] nextConfigureEvent() {
        if (ccsEvents == null) {
            ccsEvents = new ArrayList<>();
            flatten(fragment.get(), ccsEvents);
            ccsPos = 0;
        }
        Object[] e = ccsEvents.get(ccsPos++);
        if (ccsPos >= ccsEvents.size()) {
            ccsPos = 0; // allow the walk to be repeated
        }
        return e;
    }

    boolean configureWalkAtStart() {
        return ccsPos == 0;
    }

    void configured() {
        state = kind.physical ? STATE_PHYSICAL_CONFIGURED : STATE_LOGICAL_CONFIGURED;
    }

    static final String BEGIN = "begin";
    static final String PROP = "prop";
    static final String END = "end";

    private static void flatten(Fdt.Node n, List<Object[]> out) {
        out.add(new Object[] {BEGIN, n.name(), null});
        for (Map.Entry<String, byte[]> p : n.props().entrySet()) {
            out.add(new Object[] {PROP, p.getKey(), p.getValue()});
        }
        for (Fdt.Node c : n.children()) {
            flatten(c, out);
        }
        out.add(new Object[] {END, null, null});
    }
}
