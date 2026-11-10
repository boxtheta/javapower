package javapower.cpu;

import javapower.core.DecodeTable;
import javapower.core.InsnSet;

/**
 * Every non-floating-point, non-vector instruction of Power ISA 3.0B that a 64-bit server guest
 * can execute: fixed-point arithmetic/logical/rotate/compare/trap, loads and stores (including
 * load-reserve/store-conditional, string and multiple forms), branches, CR operations, and the
 * system instructions (sc/scv, rfid, MSR and SPR moves, cache and TLB management).
 *
 * <p>System instructions reach the owning {@link Interpreter} through the {@link RadixMmu}
 * installed as {@code CpuState.mem}; ALU and load/store handlers only need {@code CpuState}.
 */
public final class IntInsns implements InsnSet {
    @Override
    public void register(DecodeTable table) {
        Alu.register(table);
        LoadStore.register(table);
        BranchInsns.register(table);
        SystemInsns.register(table);
    }
}
