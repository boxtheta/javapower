package javapower.fpvec;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javapower.core.DecodeTable;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Differential tests: every golden vector generated under QEMU replayed through our Ops. */
class GoldenTest {
    static final String[] GROUPS = {
        "selftest_be", "fparith", "fpfma", "fpconv", "fpcmp", "fpscr", "fpldst", "fpldst_be",
        "vmxint", "vmxperm", "vmxfp", "vmxcrypto", "vmxscr", "vmxldst", "vmxldst_be",
        "vsxarith", "vsxfma", "vsxcmp", "vsxcvt", "vsxperm", "vsxmove", "vsxldst", "vsxldst_be",
        "qparith", "qpcvt", "qpcmp",
    };

    static DecodeTable table() {
        DecodeTable t = new DecodeTable();
        FpuSets.registerAll(t);
        return t;
    }

    @TestFactory
    Stream<DynamicNode> golden() throws IOException {
        DecodeTable t = table();
        List<DynamicNode> groups = new ArrayList<>();
        for (String g : GROUPS) {
            Map<String, List<Golden.Case>> byMn = new LinkedHashMap<>();
            for (Golden.Case c : Golden.load(g)) {
                byMn.computeIfAbsent(c.mnemonic(), k -> new ArrayList<>()).add(c);
            }
            List<DynamicNode> tests = new ArrayList<>();
            for (Map.Entry<String, List<Golden.Case>> en : byMn.entrySet()) {
                tests.add(DynamicTest.dynamicTest(en.getKey() + " (" + en.getValue().size() + ")", () -> {
                    int bad = 0;
                    StringBuilder sb = new StringBuilder();
                    for (Golden.Case c : en.getValue()) {
                        String d = Golden.run(t, c, g.endsWith("_be"));
                        if (d != null) {
                            if (bad < 8) {
                                sb.append("\n  ").append(c.asm()).append(" | in: ").append(c.in()).append("\n     ").append(d);
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
