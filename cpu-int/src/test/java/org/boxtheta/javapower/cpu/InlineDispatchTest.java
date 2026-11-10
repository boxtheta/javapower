package org.boxtheta.javapower.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.boxtheta.javapower.core.DecodeTable;
import org.boxtheta.javapower.core.Op;
import org.junit.jupiter.api.Test;

/** The run loop's inline dispatch list matches what {@link IntInsns} registers. */
class InlineDispatchTest {
    @Test
    void everyInlineHandlerIsRegisteredUnderItsName() {
        Reg r = new Reg(new DecodeTable());
        IntInsns.register(r);
        int k = 1;
        for (String name : Interpreter.inlineNames()) {
            Op op = r.registered.get(name);
            assertTrue(op instanceof Reg.Kind, name + " is not registered as an inline handler");
            assertEquals(k, ((Reg.Kind) op).kind(), name);
            assertEquals(k, Interpreter.kindOf(name), name);
            k++;
        }
        long tagged = r.registered.values().stream().filter(o -> o instanceof Reg.Kind).count();
        assertEquals(Interpreter.inlineNames().size(), tagged);
    }
}
