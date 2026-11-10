package javapower.fpvec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javapower.core.DecodeTable;
import org.junit.jupiter.api.Test;

/** Structural checks on the decode patterns registered by {@link FpuSets}. */
class DecodeTest {
    @SuppressWarnings("unchecked")
    static List<DecodeTable.Entry> entries(DecodeTable t) throws ReflectiveOperationException {
        Field f = DecodeTable.class.getDeclaredField("buckets");
        f.setAccessible(true);
        List<DecodeTable.Entry> out = new ArrayList<>();
        for (List<DecodeTable.Entry> b : (List<List<DecodeTable.Entry>>) f.get(t)) out.addAll(b);
        return out;
    }

    /**
     * Two patterns that can match the same word must be strictly nested (the more specific one
     * wins by mask size), so the outcome never depends on registration order.
     */
    @Test
    void patternsAreNestedOrDisjoint() throws ReflectiveOperationException {
        DecodeTable t = GoldenTest.table();
        List<DecodeTable.Entry> es = entries(t);
        StringBuilder bad = new StringBuilder();
        for (int i = 0; i < es.size(); i++) {
            for (int j = i + 1; j < es.size(); j++) {
                DecodeTable.Entry a = es.get(i), b = es.get(j);
                boolean overlap = ((a.match() ^ b.match()) & a.mask() & b.mask()) == 0;
                if (!overlap) continue;
                boolean aInB = (a.mask() & b.mask()) == b.mask() && a.mask() != b.mask();
                boolean bInA = (a.mask() & b.mask()) == a.mask() && a.mask() != b.mask();
                if (!aInB && !bInA) {
                    bad.append(String.format("%n  %s (%08x/%08x) vs %s (%08x/%08x)", a.name(), a.mask(), a.match(),
                            b.name(), b.mask(), b.match()));
                }
            }
        }
        assertTrue(bad.isEmpty(), "ambiguous overlapping patterns:" + bad);
    }

    /** Integer and system instructions sharing primary opcodes must not be claimed by cpu-fpvec. */
    @Test
    void doesNotShadowIntegerInstructions() throws IOException {
        DecodeTable t = GoldenTest.table();
        StringBuilder bad = new StringBuilder();
        int n = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                DecodeTest.class.getResourceAsStream("/integer_words.txt"), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] p = line.split("\\|", 2);
                int w = Integer.parseUnsignedInt(p[0], 16);
                DecodeTable.Entry e = t.lookup(w);
                n++;
                if (e != null) bad.append(String.format("%n  %s (%08x) decoded as %s", p[1], w, e.name()));
            }
        }
        assertTrue(n > 150);
        assertTrue(bad.isEmpty(), "integer instructions shadowed:" + bad);
    }

    /** Every golden vector decodes to the entry named like its mnemonic (modulo Rc/RO/EX suffixes). */
    @Test
    void goldenMnemonicsDecodeToTheirEntry() throws IOException {
        DecodeTable t = GoldenTest.table();
        StringBuilder bad = new StringBuilder();
        for (String g : GoldenTest.GROUPS) {
            for (Golden.Case c : Golden.load(g)) {
                DecodeTable.Entry e = t.lookup(c.insn());
                String mn = c.mnemonic();
                if (c.insn() == 0) continue; // the selftest's deliberately illegal word
                if (e == null || !sameInsn(mn, e.name())) {
                    bad.append(String.format("%n  %s: %s -> %s", g, c.asm(), e == null ? "null" : e.name()));
                    if (bad.length() > 4000) break;
                }
            }
        }
        assertEquals("", bad.toString());
    }

    static boolean sameInsn(String mn, String name) {
        if (mn.equals(name)) return true;
        String m = mn.endsWith(".") ? mn.substring(0, mn.length() - 1) : mn;
        if (m.equals(name)) return true;
        // round-to-odd (xsaddqpo), inexact (xsrqpix) variants
        return (m.endsWith("o") || m.endsWith("x")) && m.substring(0, m.length() - 1).equals(name);
    }
}
