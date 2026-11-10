package org.boxtheta.javapower.cpu;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.boxtheta.javapower.core.DecodeTable;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Differential tests: every golden vector generated under QEMU (cpu-int/difftest) replayed
 * through the interpreter. Cases are grouped by the part of their name before the first '-'.
 */
class GoldenTest {
    static final String[] GROUPS = {"selftest", "alu", "alu32", "branch", "ldst", "sys", "spr", "mmu"};

    @TestFactory
    Stream<DynamicNode> golden() throws IOException {
        return replay(false);
    }

    /** The same vectors with every block compiled (see BlockCompiler) before it first runs. */
    @TestFactory
    Stream<DynamicNode> goldenCompiled() throws IOException {
        return replay(true);
    }

    private Stream<DynamicNode> replay(boolean compiled) throws IOException {
        DecodeTable t = new DecodeTable();
        t.addAll(new IntInsns());
        Golden.Machine m = new Golden.Machine(t);
        if (compiled) {
            m.cpu.blockThreshold = 1;
        } else {
            m.cpu.compileBlocks = false;
        }
        List<DynamicNode> groups = new ArrayList<>();
        for (String g : GROUPS) {
            List<Golden.Case> cases;
            try {
                cases = Golden.load(g);
            } catch (IOException e) {
                continue; // group not generated yet
            }
            Map<String, List<Golden.Case>> byKey = new LinkedHashMap<>();
            for (Golden.Case c : cases) {
                int dash = c.name().indexOf('-');
                String key = dash < 0 ? c.name() : c.name().substring(0, dash);
                byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
            }
            List<DynamicNode> tests = new ArrayList<>();
            for (Map.Entry<String, List<Golden.Case>> en : byKey.entrySet()) {
                tests.add(DynamicTest.dynamicTest(en.getKey() + " (" + en.getValue().size() + ")", () -> {
                    int bad = 0;
                    StringBuilder sb = new StringBuilder();
                    for (Golden.Case c : en.getValue()) {
                        String d;
                        try {
                            d = m.run(c);
                        } catch (RuntimeException e) {
                            d = "exception " + e;
                        }
                        if (d != null) {
                            if (bad < 10) {
                                sb.append("\n  ").append(c.name()).append(" | ").append(c.inText()).append("\n    ").append(d);
                            }
                            bad++;
                        }
                    }
                    if (bad > 0) {
                        fail(bad + "/" + en.getValue().size() + " mismatches" + sb);
                    }
                }));
            }
            groups.add(DynamicContainer.dynamicContainer(g, tests));
        }
        return groups.stream();
    }
}
