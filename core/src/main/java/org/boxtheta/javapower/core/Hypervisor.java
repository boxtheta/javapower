package org.boxtheta.javapower.core;

/**
 * The PAPR hypervisor, as seen by the CPU. The guest runs with MSR[HV]=0; {@code sc 1} executed
 * with MSR[PR]=0 does not take an interrupt but calls {@link #hcall} synchronously (the same
 * "virtual hypervisor" arrangement QEMU uses for pseries).
 */
public interface Hypervisor {
    /**
     * Perform the hypercall whose opcode is in r3 and arguments in r4..r12. Writes the status to
     * r3 and any results to r4.. . May change other state (MSR, LPCR, {@code ceded}, memory).
     * Execution continues at the instruction after the {@code sc 1}.
     */
    void hcall(CpuState s);

    /**
     * The run loop of {@code s} stops executing instructions to idle or park (H_CEDE, a wait
     * instruction, the RTAS stopped state). Called on that CPU's own thread. PAPR uses it to
     * mark the vCPU preempted in its VPA dispatch counter, as QEMU's {@code cpu_exec_exit} hook does.
     */
    default void cpuExecExit(CpuState s) {
    }

    /**
     * The run loop of {@code s} resumes executing instructions after idling or parking (and once
     * when it first starts). Called on that CPU's own thread, before the first instruction.
     */
    default void cpuExecEnter(CpuState s) {
    }
}
