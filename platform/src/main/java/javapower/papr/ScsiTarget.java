package javapower.papr;

/**
 * A SCSI logical unit as seen by a host bus adapter. {@link #submit} starts a command and the
 * target eventually calls {@link ScsiRequest#complete} exactly once; today that happens before
 * {@code submit} returns, but HBAs must not depend on it (the boundary is meant to become
 * asynchronous).
 */
public interface ScsiTarget {
    /** SCSI channel (bus) number on the HBA. */
    int channel();

    /** SCSI target id on the HBA. */
    int id();

    /** Logical unit number. */
    int lun();

    void submit(ScsiRequest req);

    /** Logical unit reset (task management or bus reset): abort everything, raise a unit attention. */
    void reset();
}
