package javapower.cpu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javapower.core.DecodeTable;

/**
 * Coverage tool (not a test): reads lines "hexword mnemonic count" and prints those our integer
 * decode table does not handle. Used by cpu-int/difftest/scan.py on real guest binaries.
 */
public final class DecodeScan {
    private DecodeScan() {}

    public static void main(String[] args) throws IOException {
        DecodeTable t = new DecodeTable();
        t.addAll(new IntInsns());
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            String[] p = line.trim().split("\\s+");
            if (p.length < 2) {
                continue;
            }
            int w = (int) Long.parseLong(p[0], 16);
            DecodeTable.Entry e = t.lookup(w);
            if (e == null || e.op() == Fields.INVALID) {
                System.out.println(line.trim() + " " + (e == null ? "-" : e.name()) + " " + Interpreter.opcodeKey(w));
            }
        }
    }
}
