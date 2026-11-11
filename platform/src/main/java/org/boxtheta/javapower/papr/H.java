package org.boxtheta.javapower.papr;

/** PAPR hypercall opcodes and return codes (values as in PAPR / QEMU's spapr.h). */
final class H {
    private H() {}

    // Return codes.
    public static final long SUCCESS = 0;
    public static final long BUSY = 1;
    public static final long CLOSED = 2;
    public static final long NOT_AVAILABLE = 3;
    public static final long CONTINUE = 18;
    public static final long HARDWARE = -1;
    public static final long FUNCTION = -2;
    public static final long PRIVILEGE = -3;
    public static final long PARAMETER = -4;
    public static final long BAD_MODE = -5;
    public static final long NOT_FOUND = -7;
    public static final long RESERVED_DABR = -8;
    public static final long NO_MEM = -9;
    public static final long AUTHORITY = -10;
    public static final long PERMISSION = -11;
    public static final long DROPPED = -12;
    public static final long SOURCE_PARM = -13;
    public static final long DEST_PARM = -14;
    public static final long REMOTE_PARM = -15;
    public static final long RESOURCE = -16;
    public static final long ADAPTER_PARM = -17;
    public static final long P2 = -55;
    public static final long P3 = -56;
    public static final long P4 = -57;
    public static final long P5 = -58;
    public static final long NOOP = -63;
    public static final long UNSUPPORTED = -67;
    public static final long IN_USE = -77;
    public static final long UNSUPPORTED_FLAG = -256;
    public static final long MULTI_THREADS_ACTIVE = -9005;

    // Opcodes.
    public static final int REMOVE = 0x04;
    public static final int ENTER = 0x08;
    public static final int READ = 0x0c;
    public static final int CLEAR_MOD = 0x10;
    public static final int CLEAR_REF = 0x14;
    public static final int PROTECT = 0x18;
    public static final int GET_TCE = 0x1c;
    public static final int PUT_TCE = 0x20;
    public static final int SET_SPRG0 = 0x24;
    public static final int SET_DABR = 0x28;
    public static final int PAGE_INIT = 0x2c;
    public static final int LOGICAL_CI_LOAD = 0x3c;
    public static final int LOGICAL_CI_STORE = 0x40;
    public static final int LOGICAL_CACHE_LOAD = 0x44;
    public static final int LOGICAL_CACHE_STORE = 0x48;
    public static final int LOGICAL_ICBI = 0x4c;
    public static final int LOGICAL_DCBF = 0x50;
    public static final int GET_TERM_CHAR = 0x54;
    public static final int PUT_TERM_CHAR = 0x58;
    public static final int EOI = 0x64;
    public static final int CPPR = 0x68;
    public static final int IPI = 0x6c;
    public static final int IPOLL = 0x70;
    public static final int XIRR = 0x74;
    public static final int REGISTER_VPA = 0xdc;
    public static final int CEDE = 0xe0;
    public static final int CONFER = 0xe4;
    public static final int PROD = 0xe8;
    public static final int REG_CRQ = 0xfc;
    public static final int REGISTER_LOGICAL_LAN = 0x114;
    public static final int FREE_LOGICAL_LAN = 0x118;
    public static final int ADD_LOGICAL_LAN_BUFFER = 0x11c;
    public static final int SEND_LOGICAL_LAN = 0x120;
    public static final int MULTICAST_CTRL = 0x130;
    public static final int CHANGE_LOGICAL_LAN_MAC = 0x14c;
    public static final int FREE_CRQ = 0x100;
    public static final int VIO_SIGNAL = 0x104;
    public static final int SEND_CRQ = 0x108;
    public static final int BULK_REMOVE = 0x124;
    public static final int SET_XDABR = 0x134;
    public static final int STUFF_TCE = 0x138;
    public static final int PUT_TCE_INDIRECT = 0x13c;
    public static final int GET_CPU_CHARACTERISTICS = 0x1c8;
    public static final int JOIN = 0x298;
    public static final int ENABLE_CRQ = 0x2b0;
    public static final int GET_EM_PARMS = 0x2b8;
    public static final int HOME_NODE_ASSOCIATIVITY = 0x2ec;
    public static final int XIRR_X = 0x2fc;
    public static final int RANDOM = 0x300;
    public static final int SET_MODE = 0x31c;
    public static final int RESIZE_HPT_PREPARE = 0x36c;
    public static final int RESIZE_HPT_COMMIT = 0x370;
    public static final int CLEAN_SLB = 0x374;
    public static final int INVALIDATE_PID = 0x378;
    public static final int REGISTER_PROC_TBL = 0x37c;
    public static final int SIGNAL_SYS_RESET = 0x380;
    public static final int WATCHDOG = 0x45c;

    // QEMU/KVM private hcalls (0xf000 range), used by SLOF.
    public static final int KVMPPC_H_RTAS = 0xf000;
    public static final int KVMPPC_H_LOGICAL_MEMOP = 0xf001;
    public static final int KVMPPC_H_CAS = 0xf002;
    public static final int KVMPPC_H_UPDATE_DT = 0xf003;

    /** H_VPHN is the PAPR name of H_HOME_NODE_ASSOCIATIVITY. */
    public static final int VPHN = HOME_NODE_ASSOCIATIVITY;
}
