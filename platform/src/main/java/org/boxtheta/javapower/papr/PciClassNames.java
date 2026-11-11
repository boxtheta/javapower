package org.boxtheta.javapower.papr;

/**
 * Device tree node names of PCI functions by class code, the generic names of the PCI bus
 * binding to Open Firmware as PAPR uses them (and QEMU names its device nodes): a name for the
 * subclass, or for a few programming interfaces, where one is defined, otherwise the base
 * class's, and {@code pci} for base classes beyond the table.
 */
final class PciClassNames {
    private PciClassNames() {}

    private static final String[] BASE = {
        "legacy-device", "mass-storage", "network", "display", "multimedia-device", "memory-controller",
        "unknown-bridge", "communication-controller", "system-peripheral", "input-controller", "docking-station",
        "cpu", "serial-bus", "wireless-controller", "intelligent-io", "satellite-device", "encryption",
        "data-processing-controller",
    };

    /** The node name for a 24-bit class code. */
    static String name(int classCode) {
        int base = (classCode >>> 16) & 0xff;
        int sub = (classCode >>> 8) & 0xff;
        int prog = classCode & 0xff;
        if (base >= BASE.length) {
            return "pci";
        }
        String s = subclass(base, sub, prog);
        return s != null ? s : BASE[base];
    }

    private static String subclass(int base, int sub, int prog) {
        return switch (base) {
            case 0x00 -> sub == 0x01 ? "display" : null;
            case 0x01 -> switch (sub) {
                case 0x00 -> "scsi";
                case 0x01 -> "ide";
                case 0x02 -> "fdc";
                case 0x03 -> "ipi";
                case 0x04 -> "raid";
                case 0x05 -> "ata";
                case 0x06 -> "sata";
                case 0x07 -> "sas";
                default -> null;
            };
            case 0x02 -> switch (sub) {
                case 0x00 -> "ethernet";
                case 0x01 -> "token-ring";
                case 0x02 -> "fddi";
                case 0x03 -> "atm";
                case 0x04 -> "isdn";
                case 0x05 -> "worldfip";
                case 0x06 -> "picmg";
                default -> null;
            };
            case 0x03 -> switch (sub) {
                case 0x00 -> "vga";
                case 0x01 -> "xga";
                case 0x02 -> "3d-controller";
                default -> null;
            };
            case 0x04 -> switch (sub) {
                case 0x00 -> "video";
                case 0x01 -> "sound";
                case 0x02 -> "telephony";
                default -> null;
            };
            case 0x05 -> switch (sub) {
                case 0x00 -> "memory";
                case 0x01 -> "flash";
                default -> null;
            };
            case 0x06 -> switch (sub) {
                case 0x00 -> "host";
                case 0x01 -> "isa";
                case 0x02 -> "eisa";
                case 0x03 -> "mca";
                case 0x04 -> "pci";
                case 0x05 -> "pcmcia";
                case 0x06 -> "nubus";
                case 0x07 -> "cardbus";
                case 0x08 -> "raceway";
                case 0x09 -> "semi-transparent-pci";
                case 0x0a -> "infiniband";
                default -> null;
            };
            case 0x07 -> switch (sub) {
                case 0x00 -> "serial";
                case 0x01 -> "parallel";
                case 0x02 -> "multiport-serial";
                case 0x03 -> "modem";
                case 0x04 -> "gpib";
                case 0x05 -> "smart-card";
                default -> null;
            };
            case 0x08 -> switch (sub) {
                case 0x00 -> prog == 0x10 ? "io-apic" : prog == 0x20 ? "io-xapic" : "interrupt-controller";
                case 0x01 -> "dma-controller";
                case 0x02 -> "timer";
                case 0x03 -> "rtc";
                case 0x04 -> "hot-plug-controller";
                case 0x05 -> "sd-host-controller";
                default -> null;
            };
            case 0x09 -> switch (sub) {
                case 0x00 -> "keyboard";
                case 0x01 -> "pen";
                case 0x02 -> "mouse";
                case 0x03 -> "scanner";
                case 0x04 -> "gameport";
                default -> null;
            };
            case 0x0a -> sub == 0x00 ? "dock" : null;
            case 0x0b -> switch (sub) {
                case 0x02 -> "pentium";
                case 0x20 -> "powerpc";
                case 0x30 -> "mips";
                case 0x40 -> "co-processor";
                default -> null;
            };
            case 0x0c -> switch (sub) {
                case 0x00 -> "firewire";
                case 0x01 -> "access-bus";
                case 0x02 -> "ssa";
                case 0x03 -> switch (prog) {
                    case 0x00 -> "usb-uhci";
                    case 0x10 -> "usb-ohci";
                    case 0x20 -> "usb-ehci";
                    case 0x30 -> "usb-xhci";
                    case 0x80 -> "usb-unknown";
                    case 0xfe -> "usb-device";
                    default -> "usb";
                };
                case 0x04 -> "fibre-channel";
                case 0x05 -> "smb";
                case 0x06 -> "infiniband";
                case 0x07 -> "ipmi";
                case 0x08 -> "sercos";
                case 0x09 -> "canbus";
                default -> null;
            };
            case 0x0d -> switch (sub) {
                case 0x00 -> "irda";
                case 0x01 -> "consumer-ir";
                case 0x10 -> "rf-controller";
                case 0x11 -> "bluetooth";
                case 0x12 -> "broadband";
                default -> null;
            };
            case 0x0f -> switch (sub) {
                case 0x00 -> "satellite-tv";
                case 0x01 -> "satellite-audio";
                case 0x03 -> "satellite-voice";
                case 0x04 -> "satellite-data";
                default -> null;
            };
            case 0x10 -> switch (sub) {
                case 0x00 -> "network-encryption";
                case 0x01 -> "entertainment-encryption";
                default -> null;
            };
            case 0x11 -> switch (sub) {
                case 0x00 -> "dpio";
                case 0x01 -> "counter";
                case 0x10 -> "measurement";
                case 0x20 -> "management-card";
                default -> null;
            };
            default -> null;
        };
    }
}
