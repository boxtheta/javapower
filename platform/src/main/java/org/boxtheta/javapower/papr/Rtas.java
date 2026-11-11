package org.boxtheta.javapower.papr;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.PhysicalMemory;

/**
 * Run-time abstraction services, entered through KVMPPC_H_RTAS (SLOF's RTAS blob is a stub that
 * makes that hypercall). The argument block in guest real memory is big-endian:
 * {@code token, nargs, nret, args[nargs], rets[nret]}, 32 bits each. Semantics follow QEMU's
 * hw/ppc/spapr_rtas.c and the RTAS handlers spread over spapr_rtc.c, spapr_events.c,
 * spapr_drc.c, spapr_vio.c, spapr_pci.c, spapr_nvram.c and xics_spapr.c.
 *
 * <p>Every call runs on the calling CPU's thread with the machine lock held.
 */
final class Rtas {
    static final int TOKEN_BASE = 0x2000;
    static final int MIN_SIZE = 20;
    static final int ERROR_LOG_MAX = 2048;
    static final int EVENT_SCAN_RATE = 1;

    static final int SUCCESS = 0;
    static final int NO_ERRORS_FOUND = 1;
    static final int HW_ERROR = -1;
    static final int PARAM_ERROR = -3;
    static final int NOT_SUPPORTED = -3;
    static final int NO_SUCH_INDICATOR = -3;
    static final int NOT_AUTHORIZED = -9002;
    static final int SYSPARM_PARAM_ERROR = -9999;

    // ibm,configure-connector responses.
    static final int CC_SUCCESS = 0;
    static final int CC_NEXT_CHILD = 2;
    static final int CC_NEXT_PROPERTY = 3;
    static final int CC_PREV_PARENT = 4;
    static final int CC_NOT_CONFIGURABLE = -9003;
    private static final int CC_VAL_DATA_OFFSET = 20;
    private static final int CC_WA_LEN = 4096;

    private final Machine m;
    private final PhysicalMemory mem;
    private final PaprLog log;
    private long rtcOffsetNs;
    private long fwnmiResetAddr = -1;
    private long fwnmiMceAddr = -1;
    private int ccsDepth;

    Rtas(Machine m) {
        this.m = m;
        this.mem = m.memory();
        this.log = m.log();
    }

    void reset() {
        fwnmiResetAddr = -1;
        fwnmiMceAddr = -1;
        ccsDepth = 0;
    }

    void saveState(java.io.DataOutput out) throws java.io.IOException {
        out.writeLong(rtcOffsetNs);
        out.writeLong(fwnmiResetAddr);
        out.writeLong(fwnmiMceAddr);
        out.writeInt(ccsDepth);
    }

    /**
     * Read what {@link #saveState} wrote. The time of day keeps following the configured clock
     * with the offset the guest set, so it reads host time, not the time at which the state was
     * saved.
     */
    void loadState(org.boxtheta.javapower.core.StateInput.Record in) throws java.io.IOException {
        rtcOffsetNs = in.readLong();
        fwnmiResetAddr = in.readLong();
        fwnmiMceAddr = in.readLong();
        ccsDepth = in.readInt();
    }

    // ---- argument block access ---------------------------------------------------------------

    private int ld(long base, int n) {
        long a = Machine.toReal(base + 4L * n);
        return mem.contains(a, 4) ? mem.read32be(a) : 0;
    }

    private long ldu(long base, int n) {
        return ld(base, n) & 0xffffffffL;
    }

    private void st(long base, int n, int v) {
        long a = Machine.toReal(base + 4L * n);
        if (mem.contains(a, 4)) {
            mem.write32be(a, v);
        }
    }

    /** Handle one RTAS call; returns the hcall status. */
    long call(CpuState s, long buf) {
        int token = ld(buf, 0);
        int nargs = ld(buf, 1);
        int nret = ld(buf, 2);
        long args = buf + 12;
        long rets = args + 4L * nargs;
        int idx = token - TOKEN_BASE;
        if (idx < 0 || idx >= DeviceTree.RTAS_TOKENS.size()) {
            if (token == 0xa) {
                // Linux early debug code assumes display-character is token 0xa.
                displayCharacter(args, rets);
                return H.SUCCESS;
            }
            log.unimplemented("rtas-token-0x" + Integer.toHexString(token), "unknown RTAS token");
            st(rets, 0, PARAM_ERROR);
            return H.PARAMETER;
        }
        switch (idx) {
            case 0x00 -> displayCharacter(args, rets);
            case 0x01 -> getTimeOfDay(nargs, nret, rets);
            case 0x02 -> setTimeOfDay(nargs, nret, args, rets);
            case 0x03 -> {
                if (nargs != 2 || nret != 1) {
                    st(rets, 0, PARAM_ERROR);
                    break;
                }
                m.requestStop(Machine.ShutdownReason.POWER_OFF, "power-off");
                st(rets, 0, SUCCESS);
            }
            case 0x04 -> {
                if (nargs != 0 || nret != 1) {
                    st(rets, 0, PARAM_ERROR);
                    break;
                }
                m.requestStop(Machine.ShutdownReason.REBOOT, "system-reboot");
                st(rets, 0, SUCCESS);
            }
            case 0x05 -> { // query-cpu-stopped-state: 0 stopped, 2 running
                if (nargs != 1 || nret != 2) {
                    st(rets, 0, PARAM_ERROR);
                    break;
                }
                CpuState t = m.findCpu(ldu(args, 0));
                if (t == null) {
                    st(rets, 0, PARAM_ERROR);
                    break;
                }
                st(rets, 1, t.halted ? 0 : 2);
                st(rets, 0, SUCCESS);
            }
            case 0x06 -> { // start-cpu(id, start address, r3)
                if (nargs != 3 || nret != 1) {
                    st(rets, 0, PARAM_ERROR);
                    break;
                }
                CpuState t = m.findCpu(ldu(args, 0));
                if (t == null) {
                    st(rets, 0, PARAM_ERROR);
                    break;
                }
                st(rets, 0, m.startCpu(s, t, ldu(args, 1), ldu(args, 2)) ? SUCCESS : HW_ERROR);
            }
            case 0x07 -> // stop-self: no return words; the CPU stops until start-cpu
                    m.stopSelf(s);
            case 0x08 -> getSystemParameter(args, rets);
            case 0x09 -> {
                int p = ld(args, 0);
                st(rets, 0, p == 20 || p == 42 || p == 48 ? NOT_AUTHORIZED : NOT_SUPPORTED);
            }
            case 0x0a, 0x0b, 0x0c, 0x0d -> xics(idx, nargs, nret, args, rets);
            case 0x0e -> // check-exception: no events are ever queued
                    st(rets, 0, nargs < 6 || nargs > 7 || nret != 1 ? PARAM_ERROR : NO_ERRORS_FOUND);
            case 0x0f -> st(rets, 0, nargs != 4 || nret != 1 ? PARAM_ERROR : NO_ERRORS_FOUND);
            case 0x10 -> setTceBypass(nargs, args, rets);
            case 0x11 -> {
                if (nargs != 0) {
                    st(rets, 0, PARAM_ERROR);
                    break;
                }
                for (VioDevice d : m.vioDevices()) {
                    d.quiesce();
                }
                st(rets, 0, SUCCESS);
            }
            case 0x12 -> nvramFetch(nargs, nret, args, rets);
            case 0x13 -> nvramStore(nargs, nret, args, rets);
            case 0x14, 0x15, 0x16, 0x17 -> {
                // PCI config space: the PHB has no devices, so every access fails like an access
                // to a missing device does in QEMU.
                log.once("rtas-pci-config", "config access to the empty PHB");
                st(rets, 0, HW_ERROR);
            }
            case 0x18, 0x19 -> {
                log.once("rtas-pci-msi", DeviceTree.RTAS_TOKENS.get(idx) + " on the empty PHB");
                st(rets, 0, PARAM_ERROR);
            }
            case 0x1a -> setIndicator(nargs, nret, args, rets);
            case 0x1b -> powerLevel(nargs != 2 || nret != 2, args, rets);
            case 0x1c -> powerLevel(nargs != 1 || nret != 2, args, rets);
            case 0x1d -> getSensorState(nargs, nret, args, rets);
            case 0x1e -> configureConnector(nargs, nret, args, rets);
            case 0x1f -> osTerm(args, rets);
            case 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x29 -> {
                // EEH and DDW calls all address a PE; there are no PCI devices, hence no PEs.
                log.once("rtas-eeh-ddw", DeviceTree.RTAS_TOKENS.get(idx) + " on the empty PHB");
                st(rets, 0, PARAM_ERROR);
            }
            case 0x2a -> {
                if (nargs != 0 || nret != 1) {
                    st(rets, 0, PARAM_ERROR);
                    break;
                }
                log.unimplemented("rtas-suspend-me", "guest suspend is not supported");
                st(rets, 0, HW_ERROR);
            }
            case 0x2b -> nmiRegister(args, rets);
            case 0x2c -> st(rets, 0, fwnmiMceAddr == -1 ? PARAM_ERROR : SUCCESS);
            case 0x2d -> configureKernelDump(nargs, nret, args, rets);
            default -> throw new AssertionError(idx);
        }
        return H.SUCCESS;
    }

    private void displayCharacter(long args, long rets) {
        byte[] c = {(byte) ld(args, 0)};
        m.vty().putChars(c, 0, 1);
        st(rets, 0, SUCCESS);
    }

    // ---- time of day -------------------------------------------------------------------------

    private long hostNs() {
        java.time.Instant i = m.config().clock.instant();
        return i.getEpochSecond() * 1_000_000_000L + i.getNano();
    }

    private void getTimeOfDay(int nargs, int nret, long rets) {
        if (nargs != 0 || nret != 8) {
            st(rets, 0, PARAM_ERROR);
            return;
        }
        long ns = hostNs() + rtcOffsetNs;
        ZonedDateTime t = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(Math.floorDiv(ns, 1_000_000_000L), Math.floorMod(ns, 1_000_000_000L)),
                ZoneOffset.UTC);
        st(rets, 0, SUCCESS);
        st(rets, 1, t.getYear());
        st(rets, 2, t.getMonthValue());
        st(rets, 3, t.getDayOfMonth());
        st(rets, 4, t.getHour());
        st(rets, 5, t.getMinute());
        st(rets, 6, t.getSecond());
        st(rets, 7, t.getNano());
    }

    private void setTimeOfDay(int nargs, int nret, long args, long rets) {
        if (nargs != 7 || nret != 1) {
            st(rets, 0, PARAM_ERROR);
            return;
        }
        long secs;
        try {
            // mktimegm normalises out-of-range fields; do the same by adding them up.
            LocalDateTime base = LocalDateTime.of(ld(args, 0), 1, 1, 0, 0, 0);
            secs = base.plusMonths(ld(args, 1) - 1L).plusDays(ld(args, 2) - 1L).plusHours(ld(args, 3))
                    .plusMinutes(ld(args, 4)).plusSeconds(ld(args, 5)).toEpochSecond(ZoneOffset.UTC);
        } catch (DateTimeException | ArithmeticException e) {
            st(rets, 0, PARAM_ERROR);
            return;
        }
        rtcOffsetNs = secs * 1_000_000_000L - hostNs();
        st(rets, 0, SUCCESS);
    }

    // ---- system parameters -------------------------------------------------------------------

    private int sysparmStore(long addr, long len, byte[] val) {
        if (len < 2) {
            return SYSPARM_PARAM_ERROR;
        }
        long a = Machine.toReal(addr);
        int n = (int) Math.min(len - 2, val.length);
        if (!mem.contains(a, 2 + n)) {
            return PARAM_ERROR;
        }
        mem.write16be(a, val.length);
        mem.write(a + 2, val, 0, n);
        return SUCCESS;
    }

    private void getSystemParameter(long args, long rets) {
        int param = ld(args, 0);
        long buffer = ldu(args, 1);
        long length = ldu(args, 2);
        int r;
        switch (param) {
            case 20 -> { // SPLPAR characteristics
                int n = m.cpuCount();
                String v = String.format("MaxEntCap=%d,DesMem=%d,DesProcs=%d,MaxPlatProcs=%d", n,
                        m.config().ramSize >>> 20, n, n);
                byte[] b = (v + "\0").getBytes(StandardCharsets.US_ASCII);
                r = sysparmStore(buffer, length, b);
            }
            case 42 -> r = sysparmStore(buffer, length, new byte[] {0}); // diagnostics run mode: disabled
            case 48 -> r = sysparmStore(buffer, length, uuidBytes());
            default -> r = NOT_SUPPORTED;
        }
        st(rets, 0, r);
    }

    private byte[] uuidBytes() {
        String u = m.config().uuid;
        if (u == null) {
            return new byte[0];
        }
        java.util.UUID id = java.util.UUID.fromString(u);
        return java.nio.ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits()).array();
    }

    // ---- XICS --------------------------------------------------------------------------------

    private void xics(int idx, int nargs, int nret, long args, long rets) {
        Xics x = m.xics();
        int nr = ld(args, 0);
        switch (idx) {
            case 0x0a -> { // ibm,set-xive
                if (nargs != 3 || nret != 1) {
                    st(rets, 0, PARAM_ERROR);
                    return;
                }
                int server = ld(args, 1);
                int prio = ld(args, 2);
                if (!Xics.validIrq(nr & 0xffffffffL) || !x.hasServer(server) || Integer.compareUnsigned(prio, 0xff) > 0) {
                    st(rets, 0, PARAM_ERROR);
                    return;
                }
                x.setXive(nr, server, prio);
                st(rets, 0, SUCCESS);
            }
            case 0x0b -> { // ibm,get-xive
                if (nargs != 1 || nret != 3) {
                    st(rets, 0, PARAM_ERROR);
                    return;
                }
                if (!Xics.validIrq(nr & 0xffffffffL)) {
                    st(rets, 0, PARAM_ERROR);
                    return;
                }
                int[] v = x.getXive(nr);
                st(rets, 0, SUCCESS);
                st(rets, 1, v[0]);
                st(rets, 2, v[1]);
            }
            default -> { // ibm,int-off / ibm,int-on
                if (nargs != 1 || nret != 1) {
                    st(rets, 0, PARAM_ERROR);
                    return;
                }
                if (!Xics.validIrq(nr & 0xffffffffL)) {
                    st(rets, 0, PARAM_ERROR);
                    return;
                }
                if (idx == 0x0c) {
                    x.intOff(nr);
                } else {
                    x.intOn(nr);
                }
                st(rets, 0, SUCCESS);
            }
        }
    }

    // ---- VIO, NVRAM --------------------------------------------------------------------------

    private void setTceBypass(int nargs, long args, long rets) {
        if (nargs != 2) {
            st(rets, 0, PARAM_ERROR);
            return;
        }
        VioDevice d = m.vioDeviceByReg(ldu(args, 0));
        if (d == null || d.tce() == null) {
            st(rets, 0, PARAM_ERROR);
            return;
        }
        d.tce().setBypass(ld(args, 1) != 0);
        st(rets, 0, SUCCESS);
    }

    private void nvramFetch(int nargs, int nret, long args, long rets) {
        if (nargs != 3 || nret != 2) {
            st(rets, 0, PARAM_ERROR);
            return;
        }
        long off = ldu(args, 0);
        long buf = ldu(args, 1);
        long len = ldu(args, 2);
        if (!m.nvram().fetch(off, buf, len)) {
            st(rets, 0, PARAM_ERROR);
            st(rets, 1, 0);
            return;
        }
        st(rets, 0, SUCCESS);
        st(rets, 1, (int) len);
    }

    private void nvramStore(int nargs, int nret, long args, long rets) {
        if (nargs != 3 || nret != 2) {
            st(rets, 0, PARAM_ERROR);
            return;
        }
        long off = ldu(args, 0);
        long buf = ldu(args, 1);
        long len = ldu(args, 2);
        int r = m.nvram().store(off, buf, len);
        if (r < 0) {
            st(rets, 0, PARAM_ERROR);
            return;
        }
        st(rets, 0, r == 0 ? HW_ERROR : SUCCESS);
        st(rets, 1, r == 0 ? 0 : (int) len);
    }

    // ---- dynamic reconfiguration, power --------------------------------------------------------

    private void setIndicator(int nargs, int nret, long args, long rets) {
        if (nargs != 3 || nret != 1) {
            st(rets, 0, PARAM_ERROR);
            return;
        }
        int type = ld(args, 0);
        Drc d = m.drcByIndex(ld(args, 1));
        int state = ld(args, 2);
        int r;
        switch (type) {
            case 9001 -> r = d == null ? NO_SUCH_INDICATOR : d.setIsolation(state);
            case 9002 -> {
                if (d == null) {
                    r = NO_SUCH_INDICATOR;
                } else {
                    d.drIndicator = state;
                    r = SUCCESS;
                }
            }
            case 9003 -> r = d == null ? NO_SUCH_INDICATOR : d.setAllocation(state);
            default -> r = NOT_SUPPORTED;
        }
        st(rets, 0, r);
    }

    private void powerLevel(boolean badArgs, long args, long rets) {
        if (badArgs) {
            st(rets, 0, PARAM_ERROR);
            return;
        }
        if (ld(args, 0) != -1) {
            st(rets, 0, NOT_SUPPORTED);
            return;
        }
        st(rets, 0, SUCCESS);
        st(rets, 1, 100);
    }

    private void getSensorState(int nargs, int nret, long args, long rets) {
        int r = SUCCESS;
        int value = 0;
        if (nargs != 2 || nret != 2) {
            r = PARAM_ERROR;
        } else if (ld(args, 0) != 9003) {
            r = NOT_SUPPORTED;
        } else {
            Drc d = m.drcByIndex(ld(args, 1));
            if (d == null) {
                r = PARAM_ERROR;
            } else {
                value = d.entitySense();
            }
        }
        st(rets, 0, r);
        st(rets, 1, value);
    }

    private void ccStore(long wa, long off, byte[] b) {
        int n = (int) Math.min(b.length, CC_WA_LEN - off);
        long a = Machine.toReal(wa + off);
        if (n > 0 && mem.contains(a, n)) {
            mem.write(a, b, 0, n);
        }
    }

    private void configureConnector(int nargs, int nret, long args, long rets) {
        if (nargs != 2 || nret != 1) {
            st(rets, 0, PARAM_ERROR);
            return;
        }
        long wa = (ldu(args, 1) << 32) | ldu(args, 0);
        Drc d = m.drcByIndex(ld(wa, 0));
        if (d == null) {
            st(rets, 0, PARAM_ERROR);
            return;
        }
        if (!d.configurable() || !d.populated()) {
            st(rets, 0, CC_NOT_CONFIGURABLE);
            return;
        }
        if (d.configureWalkAtStart()) {
            ccsDepth = 0;
        }
        Object[] e = d.nextConfigureEvent();
        int resp;
        if (e[0] == Drc.BEGIN) {
            ccsDepth++;
            st(wa, 2, CC_VAL_DATA_OFFSET);
            ccStore(wa, CC_VAL_DATA_OFFSET, Fdt.strz((String) e[1]));
            resp = CC_NEXT_CHILD;
        } else if (e[0] == Drc.PROP) {
            byte[] name = Fdt.strz((String) e[1]);
            byte[] val = (byte[]) e[2];
            st(wa, 2, CC_VAL_DATA_OFFSET);
            ccStore(wa, CC_VAL_DATA_OFFSET, name);
            int dataOff = CC_VAL_DATA_OFFSET + name.length;
            st(wa, 3, val.length);
            st(wa, 4, dataOff);
            ccStore(wa, dataOff, val);
            resp = CC_NEXT_PROPERTY;
        } else {
            ccsDepth--;
            if (ccsDepth == 0) {
                d.configured();
                resp = CC_SUCCESS;
            } else {
                resp = CC_PREV_PARENT;
            }
        }
        st(rets, 0, resp);
    }

    // ---- OS termination, FWNMI, FADump ---------------------------------------------------------

    private void osTerm(long args, long rets) {
        long a = Machine.toReal(ldu(args, 0));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 511 && mem.contains(a + i, 1); i++) {
            int c = mem.read8(a + i);
            if (c == 0) {
                break;
            }
            sb.append((char) c);
        }
        log.warn("OS terminated: " + sb);
        m.requestStop(Machine.ShutdownReason.PANIC, sb.toString());
        st(rets, 0, SUCCESS);
    }

    private void nmiRegister(long args, long rets) {
        long rtasAddr = m.rtasAddr();
        if (rtasAddr == 0) {
            st(rets, 0, NOT_SUPPORTED);
            return;
        }
        long sreset = ldu(args, 0);
        long mce = ldu(args, 1);
        long limit = Math.min(32L << 20, m.rmaSize());
        if (sreset >= limit || mce >= limit) {
            st(rets, 0, PARAM_ERROR);
            return;
        }
        fwnmiResetAddr = sreset;
        fwnmiMceAddr = mce;
        st(rets, 0, SUCCESS);
    }

    /** FWNMI system reset vector registered by the guest, or -1. */
    long fwnmiSystemResetAddr() {
        return fwnmiResetAddr;
    }

    private void configureKernelDump(int nargs, int nret, long args, long rets) {
        if (nret != 1 || nargs != 3) {
            st(rets, 0, PARAM_ERROR);
            return;
        }
        switch (ld(args, 0)) {
            case 1 -> {
                log.unimplemented("fadump-register", "firmware-assisted dump is not supported");
                st(rets, 0, HW_ERROR);
            }
            case 2, 3 -> st(rets, 0, SUCCESS);
            default -> st(rets, 0, PARAM_ERROR);
        }
    }
}
